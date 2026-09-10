package com.enviouse.futureshopsp.server.transaction;

import com.enviouse.futureshopsp.catalog.ItemDef;
import com.enviouse.futureshopsp.catalog.ShopCatalog;
import com.enviouse.futureshopsp.event.ShopTransactionEvent;
import com.enviouse.futureshopsp.network.ShopPackets;
import com.enviouse.futureshopsp.network.packets.C2SBuyRequestPacket;
import com.enviouse.futureshopsp.network.packets.S2CBuyResponsePacket;
import com.enviouse.futureshopsp.server.economy.BalanceManager;
import com.enviouse.futureshopsp.server.economy.EconomyRecordChecksum;
import com.enviouse.futureshopsp.server.economy.EconomyTransactionCoordinator;
import com.enviouse.futureshopsp.server.economy.CustodyState;
import com.enviouse.futureshopsp.api.economy.BalanceSnapshot;
import com.enviouse.futureshopsp.api.economy.MutationKind;
import com.enviouse.futureshopsp.api.economy.MutationReceipt;
import com.enviouse.futureshopsp.api.economy.MutationRequest;
import com.enviouse.futureshopsp.api.economy.ProviderError;
import com.enviouse.futureshopsp.api.economy.ProviderResult;
import com.enviouse.futureshopsp.api.economy.ProviderResultStatus;
import com.enviouse.futureshopsp.api.economy.RequestId;
import com.enviouse.futureshopsp.server.pricing.DynamicPricingEngine;
import com.enviouse.futureshopsp.server.session.ShopSession;
import com.enviouse.futureshopsp.server.session.ShopSessionManager;
import com.enviouse.futureshopsp.server.shop.InventorySyncService;
import com.enviouse.futureshopsp.server.shop.ShopDataService;
import com.enviouse.futureshopsp.server.shop.ShopResultCode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Server-authoritative buy transaction engine for detail purchases and cart checkout.
 */
public final class ShopBuyService {
    private static final int MAX_CART_LINES = 256;

    private ShopBuyService() {
    }

    public static void handleBuyRequest(ServerPlayer player, C2SBuyRequestPacket packet) {
        BuyResult result = execute(player, packet);
        long snapshotRevision = ShopSessionManager.get(player.getUUID())
                .map(ShopSession::snapshotRevision).orElse(0L);
        ShopPackets.sendToPlayer(player, new S2CBuyResponsePacket(
                result.success(),
                packet.cartCheckout(),
                result.shopId(),
                result.errorCode(),
                result.resultingBalance(),
                result.totalQuantity(),
                result.totalCost(),
                result.balanceAvailable(),
                snapshotRevision,
                result.success() ? "accepted" : result.errorCode() == ShopResultCode.STALE_REQUEST
                        ? "stale_snapshot" : "rejected"));

        if (result.errorCode() == ShopResultCode.STALE_REQUEST && player.getServer() != null) {
            ShopDataService.sendShopData(player, result.shopId(), false, false);
        }

        if (result.success() && player.getServer() != null) {
            for (PreparedLine line : result.lines()) {
                long lineCost = line.lineCost();
                // History + the public ShopTransactionEvent carry the registry itemId (a valid
                // ResourceLocation the history screen resolves to a display name, and what downstream
                // mods expect). Dynamic pricing is keyed by listingId so per-variant pricing stays
                // independent — and == itemId for legacy entries, so existing pricing state is preserved.
                TransactionHistoryService.record(player, result.shopId(), "BUY", line.itemId(), line.quantity(), lineCost,
                        packet.cartCheckout() ? "CART" : "DETAIL");
                // Record buy activity for dynamic pricing (spec §30)
                DynamicPricingEngine.recordBuy(player.getServer(), result.shopId(), line.listingId(), line.quantity());
                // Fire ShopTransactionEvent.Post (spec §33)
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                        new ShopTransactionEvent.Post(player.getUUID(), result.shopId(), line.itemId(),
                                line.quantity(), "BUY", lineCost, result.resultingBalance()));
            }
            InventorySyncService.sendOwnedCounts(player, result.shopId());
            ShopDataService.resendSessionsViewingShop(player.getServer(), result.shopId());
        }
    }

    private static BuyResult execute(ServerPlayer player, C2SBuyRequestPacket packet) {
        String shopId = ShopDataService.resolveShopId(packet.shopId());
        ShopSession session = ShopSessionManager.get(player.getUUID()).orElse(null);
        if (session == null || !session.shopId().equals(shopId)) {
            return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.SHOP_CLOSED);
        }
        if (packet.snapshotRevision() != session.snapshotRevision()) {
            return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.STALE_REQUEST);
        }

        Map<String, Integer> mergedLines = mergeLines(packet.lineItems());
        if (mergedLines.isEmpty()) {
            return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_ITEM);
        }

        ReentrantLock lock = ShopTransactionUtil.lockFor(player.getUUID());
        if (!lock.tryLock()) {
            return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.COOLDOWN);
        }

        try {
            Inventory inventory = player.getInventory();
            long totalCost = 0L;
            int totalQuantity = 0;
            List<PreparedLine> preparedLines = new ArrayList<>();
            List<ItemStack> rewards = new ArrayList<>();

            long nowSec = System.currentTimeMillis() / 1000L;
            for (Map.Entry<String, Integer> entry : mergedLines.entrySet()) {
                // The wire line carries a listingId (catalog resolution key), NOT necessarily a
                // registry id. Resolve the exact listing by it, then mint from the def's registry itemId.
                String listingId = entry.getKey();
                int quantity = entry.getValue();
                if (quantity <= 0 || quantity > ShopTransactionUtil.MAX_BUY_QUANTITY) {
                    return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_ITEM);
                }
                if (listingId == null || listingId.isBlank()) {
                    return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_ITEM);
                }

                ItemDef itemDef = ShopCatalog.getItem(shopId, listingId).orElse(null);
                if (itemDef == null || itemDef.buyPriceMinorUnits() <= 0L) {
                    return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_ITEM);
                }
                // Availability-window re-check: the client may hold a stale catalog in which this
                // listing was still live. Never sell an expired listing.
                if (itemDef.isExpired(nowSec)) {
                    return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_ITEM);
                }

                // Registry id is what we mint/resolve; reject Air here (a listingId is never "air").
                String itemId = itemDef.itemId();
                if (itemId == null || itemId.isBlank() || "minecraft:air".equals(itemId)) {
                    return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_ITEM);
                }

                int currentStock = ShopCatalog.getCurrentStock(shopId, listingId);
                if (currentStock >= 0 && currentStock < quantity) {
                    return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.OUT_OF_STOCK);
                }

                Item item = ShopTransactionUtil.resolveItem(itemId);
                if (item == null) {
                    return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_ITEM);
                }

                long lineCost = ShopCatalog.calculateLineCost(shopId, listingId, quantity);
                if (lineCost <= 0L) {
                    return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_ITEM);
                }

                try {
                    totalCost = Math.addExact(totalCost, lineCost);
                } catch (ArithmeticException ex) {
                    return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.SERVER_ERROR);
                }

                try {
                    totalQuantity = Math.addExact(totalQuantity, quantity);
                } catch (ArithmeticException ex) {
                    return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.SERVER_ERROR);
                }
                ItemStack rewardStack = new ItemStack(item, quantity);
                // Apply saved NBT (SNBT) when present so enchanted books, Tacz guns,
                // named/lored items round-trip through /shop buys with their tag
                // intact. Empty / unparseable nbt falls through to the bare stack.
                String nbt = itemDef.nbtJson();
                if (nbt != null && !nbt.isBlank()) {
                    try {
                        net.minecraft.core.component.DataComponentPatch tag = NbtMatchUtil.snbtToPatchMigrating(player.level().registryAccess(), net.minecraft.resources.ResourceLocation.parse(itemId), nbt);
                        if (!tag.isEmpty()) rewardStack.applyComponents(tag);
                    } catch (Exception exception) {
                        // Invalid catalog SNBT must fail closed so a malformed listing cannot deliver a different item variant.
                        com.mojang.logging.LogUtils.getLogger().warn(
                                "[FutureShops] Invalid SNBT for catalog item '{}' (listing '{}') in shop '{}' — rejecting buy.",
                                itemId, listingId, shopId, exception);
                        return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_ITEM);
                    }
                }
                rewards.add(rewardStack.copy());
                preparedLines.add(new PreparedLine(listingId, itemId, quantity, lineCost));
            }

            if (!ShopTransactionUtil.canFit(inventory, rewards)) {
                    return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVENTORY_FULL);
            }

            // Fire cancellable ShopTransactionEvent.Pre (spec §33) — allows other mods to cancel or modify price
            long adjustedTotalCost = 0L;
            for (int lineIndex = 0; lineIndex < preparedLines.size(); lineIndex++) {
                PreparedLine line = preparedLines.get(lineIndex);
                ShopTransactionEvent.Pre preEvent = new ShopTransactionEvent.Pre(
                        player, shopId, line.itemId(), line.quantity(), "BUY", line.lineCost());
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(preEvent);
                if (preEvent.isCanceled()) {
                    return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.CANCELLED_BY_EVENT);
                }
                long adjustedCost = preEvent.getPriceMinor();
                if (adjustedCost <= 0L) {
                    return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_AMOUNT);
                }
                try {
                    adjustedTotalCost = Math.addExact(adjustedTotalCost, adjustedCost);
                } catch (ArithmeticException ex) {
                    return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.SERVER_ERROR);
                }
                preparedLines.set(lineIndex,
                        new PreparedLine(line.listingId(), line.itemId(), line.quantity(), adjustedCost));
            }
            totalCost = adjustedTotalCost;

            BalanceView currentBalance = balanceView(player.getUUID());
            if (!currentBalance.available() || currentBalance.amount() < totalCost) {
                return BuyResult.error(shopId, currentBalance, currentBalance.available()
                        ? ShopResultCode.INSUFFICIENT_FUNDS : ShopResultCode.SERVER_ERROR);
            }

            EconomyTransactionCoordinator coordinator = BalanceManager.getCoordinator();
            RequestId requestId = RequestId.random();
            MutationRequest debitRequest = MutationRequest.forPlayer(requestId, player.getUUID(), totalCost,
                    MutationKind.WITHDRAW);
            ProviderResult<BalanceSnapshot> preflight = coordinator.preflight(debitRequest);
            if (!preflight.confirmed()) {
                return BuyResult.error(shopId, balanceView(player.getUUID()), mapError(preflight.error()));
            }

            List<PreparedLine> reserved = new ArrayList<>();
            for (PreparedLine line : preparedLines) {
                if (!ShopCatalog.reserveStock(shopId, line.listingId(), line.quantity())) {
                    for (PreparedLine rollback : reserved) {
                        ShopCatalog.restoreStock(shopId, rollback.listingId(), rollback.quantity());
                    }
                    return BuyResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.OUT_OF_STOCK);
                }
                reserved.add(line);
            }

            // The coordinator persists the delivery entitlement before invoking the provider.
            String custodyItem = "shop:" + shopId;
            String custodyHash = EconomyRecordChecksum.sha256(preparedLines.toString());
            ProviderResult<MutationReceipt> withdrawal = coordinator.executeWithCustody(debitRequest,
                    player.getUUID(), custodyItem, totalQuantity, custodyHash, CustodyState.DELIVERED);
            if (!withdrawal.confirmed()) {
                if (withdrawal.status() != ProviderResultStatus.AMBIGUOUS
                        && withdrawal.status() != ProviderResultStatus.RECOVERY_REQUIRED) {
                    for (PreparedLine rollback : reserved) {
                        ShopCatalog.restoreStock(shopId, rollback.listingId(), rollback.quantity());
                    }
                }
                long balance = withdrawal.receipt().flatMap(receipt -> receipt.resultingBalanceMinorUnits().isPresent()
                        ? java.util.Optional.of(receipt.resultingBalanceMinorUnits().getAsLong()) : java.util.Optional.empty())
                        .orElseGet(() -> balanceView(player.getUUID()).amount());
                return BuyResult.error(shopId, new BalanceView(balance,
                                withdrawal.receipt().flatMap(receipt -> receipt.resultingBalanceMinorUnits().isPresent()
                                        ? java.util.Optional.of(receipt.resultingBalanceMinorUnits().getAsLong()) : java.util.Optional.empty()).isPresent()),
                        mapError(withdrawal.error()));
            }

            RequestId custodyId = debitRequest.requestId().child("custody");
            if (!ShopTransactionUtil.insertIntoInventory(inventory, rewards)) {
                // Keep the durable delivery entitlement unresolved. Never drop a paid reward or
                // claim custody after an unproven inventory insertion failure.
                coordinator.markRecoveryRequired("shop delivery requires recovery");
                return BuyResult.error(shopId, withdrawal.receipt().flatMap(receipt -> receipt.resultingBalanceMinorUnits().isPresent()
                        ? java.util.Optional.of(receipt.resultingBalanceMinorUnits().getAsLong()) : java.util.Optional.empty())
                        .map(value -> new BalanceView(value, true)).orElseGet(() -> balanceView(player.getUUID())),
                        ShopResultCode.RECOVERY_REQUIRED);
            }

            try {
                coordinator.claimCustody(custodyId);
            } catch (RuntimeException exception) {
                coordinator.markRecoveryRequired("shop delivery claim requires recovery");
                return BuyResult.error(shopId, withdrawal.receipt().flatMap(receipt -> receipt.resultingBalanceMinorUnits().isPresent()
                        ? java.util.Optional.of(receipt.resultingBalanceMinorUnits().getAsLong()) : java.util.Optional.empty())
                        .map(value -> new BalanceView(value, true)).orElseGet(() -> balanceView(player.getUUID())), ShopResultCode.RECOVERY_REQUIRED);
            }

            inventory.setChanged();
            player.inventoryMenu.broadcastChanges();
            long resultingBalance = withdrawal.receipt().flatMap(receipt -> receipt.resultingBalanceMinorUnits().isPresent()
                    ? java.util.Optional.of(receipt.resultingBalanceMinorUnits().getAsLong()) : java.util.Optional.empty())
                    .orElse(0L);
            return BuyResult.success(shopId, resultingBalance, totalCost, totalQuantity, List.copyOf(preparedLines),
                    withdrawal.receipt().flatMap(receipt -> receipt.resultingBalanceMinorUnits().isPresent()
                            ? java.util.Optional.of(receipt.resultingBalanceMinorUnits().getAsLong()) : java.util.Optional.empty()).isPresent());
        } finally {
            lock.unlock();
        }
    }

    // package-private for unit testing (multi-variant merge-by-listingId)
    static Map<String, Integer> mergeLines(List<C2SBuyRequestPacket.LineItem> lineItems) {
        if (lineItems == null || lineItems.size() > MAX_CART_LINES) {
            return Map.of();
        }
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (C2SBuyRequestPacket.LineItem lineItem : lineItems) {
            if (lineItem == null || lineItem.listingId() == null || lineItem.listingId().isBlank()
                    || lineItem.listingId().length() > 128 || lineItem.quantity() <= 0
                    || lineItem.quantity() > ShopTransactionUtil.MAX_BUY_QUANTITY) {
                return Map.of();
            }
            // Merge by listingId: two listings sharing a registry itemId but distinct listingIds
            // must NOT collapse — that is the whole point of multi-variant NBT listings.
            try {
                merged.merge(lineItem.listingId(), lineItem.quantity(), Math::addExact);
            } catch (ArithmeticException ex) {
                // Reject an overflowing duplicate listing before catalog or economy state changes.
                return Map.of();
            }
        }
        return merged;
    }

    /** {@code listingId} is the catalog resolution key; {@code itemId} is the registry id to mint/log. */
    private record PreparedLine(String listingId, String itemId, int quantity, long lineCost) {
    }

    private record BuyResult(boolean success, String shopId, ShopResultCode errorCode, long resultingBalance, long totalCost, int totalQuantity,
                             List<PreparedLine> lines, boolean balanceAvailable) {
        private static BuyResult success(String shopId, long resultingBalance, long totalCost, int totalQuantity,
                                         List<PreparedLine> lines, boolean balanceAvailable) {
            return new BuyResult(true, shopId, ShopResultCode.OK, resultingBalance, totalCost, totalQuantity, lines, balanceAvailable);
        }

        private static BuyResult error(String shopId, long resultingBalance, ShopResultCode errorCode) {
            return new BuyResult(false, shopId, errorCode, resultingBalance, 0L, 0, List.of(), true);
        }

        private static BuyResult error(String shopId, BalanceView balance, ShopResultCode errorCode) {
            return new BuyResult(false, shopId, errorCode, balance.amount(), 0L, 0, List.of(), balance.available());
        }
    }

    private record BalanceView(long amount, boolean available) {
    }

    private static BalanceView balanceView(java.util.UUID playerId) {
        ProviderResult<BalanceSnapshot> result = BalanceManager.queryBalance(playerId);
        return new BalanceView(result.value().map(BalanceSnapshot::balanceMinorUnits).orElse(0L), result.confirmed());
    }

    private static ShopResultCode mapError(ProviderError error) {
        return switch (error) {
            case INSUFFICIENT_FUNDS -> ShopResultCode.INSUFFICIENT_FUNDS;
            case INVALID_REQUEST, INVALID_AMOUNT -> ShopResultCode.INVALID_AMOUNT;
            case CAPABILITY_MISSING, NOT_READY, INCOMPATIBLE, RECEIPT_NOT_FOUND,
                 PROVIDER_EXCEPTION, TIMEOUT, UNKNOWN -> ShopResultCode.SERVER_ERROR;
            default -> ShopResultCode.SERVER_ERROR;
        };
    }
}
