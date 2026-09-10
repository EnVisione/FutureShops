package com.enviouse.futureshopsp.server.transaction;

import com.enviouse.futureshopsp.catalog.ItemDef;
import com.enviouse.futureshopsp.catalog.ShopCatalog;
import com.enviouse.futureshopsp.event.ShopTransactionEvent;
import com.enviouse.futureshopsp.network.ShopPackets;
import com.enviouse.futureshopsp.network.packets.C2SSellRequestPacket;
import com.enviouse.futureshopsp.network.packets.S2CSellResponsePacket;
import com.enviouse.futureshopsp.api.economy.BalanceSnapshot;
import com.enviouse.futureshopsp.api.economy.MutationKind;
import com.enviouse.futureshopsp.api.economy.MutationReceipt;
import com.enviouse.futureshopsp.api.economy.MutationRequest;
import com.enviouse.futureshopsp.api.economy.ProviderError;
import com.enviouse.futureshopsp.api.economy.ProviderResult;
import com.enviouse.futureshopsp.api.economy.ProviderResultStatus;
import com.enviouse.futureshopsp.api.economy.RequestId;
import com.enviouse.futureshopsp.server.economy.BalanceManager;
import com.enviouse.futureshopsp.server.economy.CustodyState;
import com.enviouse.futureshopsp.server.economy.EconomyRecordChecksum;
import com.enviouse.futureshopsp.server.economy.EconomyTransactionCoordinator;
import com.enviouse.futureshopsp.server.pricing.DynamicPricingEngine;
import com.enviouse.futureshopsp.server.session.ShopSession;
import com.enviouse.futureshopsp.server.session.ShopSessionManager;
import com.enviouse.futureshopsp.server.shop.InventorySyncService;
import com.enviouse.futureshopsp.server.shop.ShopDataService;
import com.enviouse.futureshopsp.server.shop.ShopResultCode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;

import java.util.concurrent.locks.ReentrantLock;

/** Server-authoritative sell transaction path. */
public final class ShopSellService {
    private ShopSellService() {
    }

    public static void handleSellRequest(ServerPlayer player, C2SSellRequestPacket packet) {
        SellResult result = execute(player, packet);
        long snapshotRevision = ShopSessionManager.get(player.getUUID())
                .map(ShopSession::snapshotRevision).orElse(0L);
        // Resolve the registry id for the client echo, history, and the public event (all want a valid
        // ResourceLocation). Dynamic pricing stays keyed by the listingId. Fall back to the listingId
        // if the listing vanished between request and now.
        String registryItemId = ShopCatalog.getItem(result.shopId(), packet.listingId())
                .map(ItemDef::itemId).orElse(packet.listingId());
        ShopPackets.sendToPlayer(player, new S2CSellResponsePacket(
                result.success(),
                result.shopId(),
                registryItemId,
                result.errorCode(),
                result.resultingBalance(),
                packet.quantity(),
                result.totalValue(),
                result.balanceAvailable(),
                snapshotRevision,
                result.success() ? "accepted" : result.errorCode() == ShopResultCode.STALE_REQUEST
                        ? "stale_snapshot" : "rejected"));

        if (result.errorCode() == ShopResultCode.STALE_REQUEST && player.getServer() != null) {
            ShopDataService.sendShopData(player, result.shopId(), false, false);
        }

        if (result.success() && player.getServer() != null) {
            TransactionHistoryService.record(player, result.shopId(), "SELL", registryItemId, packet.quantity(), result.totalValue(), "DETAIL");
            // Record sell activity for dynamic pricing (spec §30) — keyed by listingId.
            DynamicPricingEngine.recordSell(player.getServer(), result.shopId(), packet.listingId(), packet.quantity());
            // Fire ShopTransactionEvent.Post (spec §33)
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new ShopTransactionEvent.Post(player.getUUID(), result.shopId(), registryItemId,
                            packet.quantity(), "SELL", result.totalValue(), result.resultingBalance()));
            InventorySyncService.sendOwnedCounts(player, result.shopId());
            ShopDataService.resendSessionsViewingShop(player.getServer(), result.shopId());
        }
    }

    private static SellResult execute(ServerPlayer player, C2SSellRequestPacket packet) {
        String shopId = ShopDataService.resolveShopId(packet.shopId());
        ShopSession session = ShopSessionManager.get(player.getUUID()).orElse(null);
        if (session == null || !session.shopId().equals(shopId)) {
            return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.SHOP_CLOSED);
        }
        if (packet.snapshotRevision() != session.snapshotRevision()) {
            return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.STALE_REQUEST);
        }

        int quantity = packet.quantity();
        if (quantity <= 0 || quantity > ShopTransactionUtil.MAX_SELL_QUANTITY) {
            return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_AMOUNT);
        }
        // The wire line carries a listingId (catalog resolution key), not necessarily a registry id.
        if (packet.listingId() == null || packet.listingId().isBlank()) {
            return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_ITEM);
        }

        ReentrantLock lock = ShopTransactionUtil.lockFor(player.getUUID());
        if (!lock.tryLock()) {
            return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.COOLDOWN);
        }

        try {
            ItemDef itemDef = ShopCatalog.getItem(shopId, packet.listingId()).orElse(null);
            if (itemDef == null || itemDef.sellPriceMinorUnits() <= 0L) {
                return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_ITEM);
            }
            // Availability-window re-check (stale client catalog).
            if (itemDef.isExpired(System.currentTimeMillis() / 1000L)) {
                return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_ITEM);
            }

            // Reject Air outright. Some modded bundles report their primary item as air until fully
            // constructed (Create's locomotive-like assemblies) — those must never be a valid
            // sell-target or the transaction row would immortalize a bogus itemId. Resolve from the
            // listing's registry id, not the listingId (which need not be a ResourceLocation).
            String itemId = itemDef.itemId();
            if (itemId == null || itemId.isBlank() || "minecraft:air".equals(itemId)) {
                return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_ITEM);
            }
            Item item = ShopTransactionUtil.resolveItem(itemId);
            if (item == null) {
                return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_ITEM);
            }

            // NBT-aware match: for an NBT-keyed listing (enchanted book, Tacz gun, …) the player must
            // hand over the exact tagged variant; for a bare listing requiredTag stays null so only
            // plain/tag-less stacks match (prevents dumping enchanted/damaged gear as a plain item).
            net.minecraft.core.component.DataComponentPatch requiredTag;
            try {
                requiredTag = NbtMatchUtil.snbtToPatchMigrating(player.level().registryAccess(),
                        net.minecraft.resources.ResourceLocation.parse(itemId), itemDef.nbtJson());
            } catch (RuntimeException exception) {
                com.mojang.logging.LogUtils.getLogger().warn(
                        "[FutureShops] Invalid SNBT for sell listing '{}' in shop '{}' — rejecting sell.",
                        packet.listingId(), shopId, exception);
                return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_ITEM);
            }

            Inventory inventory = player.getInventory();
            if (ShopTransactionUtil.countItems(inventory, item, true, requiredTag) < quantity) {
                return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.MISSING_ITEMS);
            }

            long totalValue;
            try {
                totalValue = Math.multiplyExact(itemDef.sellPriceMinorUnits(), quantity);
            } catch (ArithmeticException ex) {
                return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.SERVER_ERROR);
            }

            // Fire cancellable ShopTransactionEvent.Pre (spec §33) — registry itemId for API consumers.
            ShopTransactionEvent.Pre preEvent = new ShopTransactionEvent.Pre(
                    player, shopId, itemId, quantity, "SELL", totalValue);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(preEvent);
            if (preEvent.isCanceled()) {
                return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.CANCELLED_BY_EVENT);
            }
            // Allow event listeners to modify the price
            totalValue = preEvent.getPriceMinor();
            if (totalValue <= 0L) {
                return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.INVALID_AMOUNT);
            }

            EconomyTransactionCoordinator coordinator = BalanceManager.getCoordinator();
            RequestId requestId = RequestId.random();
            MutationRequest creditRequest = MutationRequest.forPlayer(requestId, player.getUUID(), totalValue,
                    MutationKind.DEPOSIT);
            ProviderResult<BalanceSnapshot> preflight = coordinator.preflight(creditRequest);
            if (!preflight.confirmed()) {
                return SellResult.error(shopId, balanceView(player.getUUID()), mapError(preflight.error()));
            }
            RequestId custodyId = creditRequest.requestId().child("custody");
            try {
                coordinator.holdCustody(custodyId, player.getUUID(), itemId, quantity,
                        EconomyRecordChecksum.sha256(itemId + "|" + itemDef.nbtJson()));
            } catch (RuntimeException exception) {
                return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.SERVER_ERROR);
            }

            if (!ShopTransactionUtil.removeItems(inventory, item, quantity, true, requiredTag)) {
                try {
                    coordinator.releaseCustody(custodyId);
                } catch (RuntimeException exception) {
                    coordinator.markRecoveryRequired("sell custody release requires recovery");
                    return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.RECOVERY_REQUIRED);
                }
                return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.MISSING_ITEMS);
            }

            ProviderResult<MutationReceipt> deposit = coordinator.executeWithCustody(creditRequest,
                    player.getUUID(), itemId, quantity,
                    EconomyRecordChecksum.sha256(itemId + "|" + itemDef.nbtJson()), CustodyState.HELD, false);
            if (!deposit.confirmed()) {
                if (deposit.status() != ProviderResultStatus.AMBIGUOUS
                        && deposit.status() != ProviderResultStatus.RECOVERY_REQUIRED) {
                    boolean restored = ShopTransactionUtil.insertIntoInventory(inventory,
                            java.util.List.of(refundStack(item, quantity, requiredTag)));
                    if (restored) {
                        inventory.setChanged();
                        try {
                            coordinator.releaseCustody(custodyId);
                        } catch (RuntimeException exception) {
                            coordinator.markRecoveryRequired("sell custody release requires recovery");
                            return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.RECOVERY_REQUIRED);
                        }
                    } else {
                        coordinator.markRecoveryRequired("sell item restoration requires recovery");
                        return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.RECOVERY_REQUIRED);
                    }
                }
                long balance = deposit.receipt().flatMap(receipt -> receipt.resultingBalanceMinorUnits().isPresent()
                        ? java.util.Optional.of(receipt.resultingBalanceMinorUnits().getAsLong()) : java.util.Optional.empty())
                        .orElseGet(() -> balanceView(player.getUUID()).amount());
                return SellResult.error(shopId, new BalanceView(balance,
                                deposit.receipt().flatMap(receipt -> receipt.resultingBalanceMinorUnits().isPresent()
                                        ? java.util.Optional.of(receipt.resultingBalanceMinorUnits().getAsLong()) : java.util.Optional.empty()).isPresent()),
                        mapError(deposit.error()));
            }

            if (!ShopCatalog.incrementStock(shopId, packet.listingId(), quantity)) {
                MutationRequest compensationRequest = MutationRequest.forPlayer(requestId.child("sell compensation"),
                        player.getUUID(), totalValue, MutationKind.WITHDRAW);
                ProviderResult<MutationReceipt> compensation = coordinator.withdraw(compensationRequest);
                if (compensation.confirmed()) {
                    boolean restored = ShopTransactionUtil.insertIntoInventory(inventory,
                            java.util.List.of(refundStack(item, quantity, requiredTag)));
                    if (restored) {
                        inventory.setChanged();
                        try {
                            coordinator.releaseCustody(custodyId);
                        } catch (RuntimeException exception) {
                            coordinator.markRecoveryRequired("sell compensation custody release requires recovery");
                            return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.RECOVERY_REQUIRED);
                        }
                    } else {
                        coordinator.markRecoveryRequired("sell compensation item restoration requires recovery");
                        return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.RECOVERY_REQUIRED);
                    }
                } else {
                    coordinator.markRecoveryRequired("sell compensation requires recovery");
                    return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.RECOVERY_REQUIRED);
                }
                return SellResult.error(shopId, balanceView(player.getUUID()),
                        ShopResultCode.SERVER_ERROR);
            }

            try {
                coordinator.releaseCustody(custodyId);
            } catch (RuntimeException exception) {
                coordinator.markRecoveryRequired("sell custody release requires recovery");
                return SellResult.error(shopId, balanceView(player.getUUID()), ShopResultCode.RECOVERY_REQUIRED);
            }

            inventory.setChanged();
            player.inventoryMenu.broadcastChanges();
            long resultingBalance = deposit.receipt().flatMap(receipt -> receipt.resultingBalanceMinorUnits().isPresent()
                    ? java.util.Optional.of(receipt.resultingBalanceMinorUnits().getAsLong()) : java.util.Optional.empty())
                    .orElse(0L);
            return SellResult.success(shopId, resultingBalance, totalValue,
                    deposit.receipt().flatMap(receipt -> receipt.resultingBalanceMinorUnits().isPresent()
                            ? java.util.Optional.of(receipt.resultingBalanceMinorUnits().getAsLong()) : java.util.Optional.empty()).isPresent());
        } finally {
            lock.unlock();
        }
    }

    /** Refund stack carrying the listing's NBT (when any), used to return items on a failed payout. */
    private static net.minecraft.world.item.ItemStack refundStack(Item item, int quantity, net.minecraft.core.component.DataComponentPatch patch) {
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item, quantity);
        if (patch != null && !patch.isEmpty()) { stack.applyComponents(patch); }
        return stack;
    }

    private record SellResult(boolean success, String shopId, ShopResultCode errorCode, long resultingBalance, long totalValue,
                              boolean balanceAvailable) {
        private static SellResult success(String shopId, long resultingBalance, long totalValue, boolean balanceAvailable) {
            return new SellResult(true, shopId, ShopResultCode.OK, resultingBalance, totalValue, balanceAvailable);
        }

        private static SellResult error(String shopId, long resultingBalance, ShopResultCode errorCode) {
            return new SellResult(false, shopId, errorCode, resultingBalance, 0L, true);
        }

        private static SellResult error(String shopId, BalanceView balance, ShopResultCode errorCode) {
            return new SellResult(false, shopId, errorCode, balance.amount(), 0L, balance.available());
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
            default -> ShopResultCode.SERVER_ERROR;
        };
    }
}
