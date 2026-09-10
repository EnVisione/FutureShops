package com.enviouse.futureshopsp.client;

import com.enviouse.futureshopsp.client.screen.BarterScreen;
import com.enviouse.futureshopsp.client.screen.CartScreen;
import com.enviouse.futureshopsp.client.screen.FranchiseManagementScreen;
import com.enviouse.futureshopsp.client.screen.ItemDetailScreen;
import com.enviouse.futureshopsp.client.screen.PlayerShopBarterScreen;
import com.enviouse.futureshopsp.client.screen.PlayerShopBlockScreen;
import com.enviouse.futureshopsp.client.screen.BalTopOverviewScreen;
import com.enviouse.futureshopsp.client.screen.BalanceOverviewScreen;
import com.enviouse.futureshopsp.client.screen.ShopMainScreen;
import com.enviouse.futureshopsp.client.screen.ShopScreenMarker;
import com.enviouse.futureshopsp.client.screen.ShopUiUtil;
import com.enviouse.futureshopsp.client.screen.TransactionHistoryScreen;
import com.enviouse.futureshopsp.network.packets.S2CBalTopUiPacket;
import com.enviouse.futureshopsp.network.packets.S2CBalanceUiPacket;
import com.enviouse.futureshopsp.network.ShopPackets;
import com.enviouse.futureshopsp.network.packets.S2CBarterResponsePacket;
import com.enviouse.futureshopsp.network.packets.S2CBuyResponsePacket;
import com.enviouse.futureshopsp.network.packets.S2CForceClosePacket;
import com.enviouse.futureshopsp.network.packets.S2CFranchiseDataPacket;
import com.enviouse.futureshopsp.network.packets.S2CHistoryResponsePacket;
import com.enviouse.futureshopsp.network.packets.S2CInventorySyncPacket;
import com.enviouse.futureshopsp.network.packets.S2CLocalShopsPacket;
import com.enviouse.futureshopsp.network.packets.S2CPlayerShopDataPacket;
import com.enviouse.futureshopsp.network.packets.S2CPlayerShopResultPacket;
import com.enviouse.futureshopsp.network.packets.S2CSettlementHistoryPacket;
import com.enviouse.futureshopsp.network.packets.S2CSellResponsePacket;
import com.enviouse.futureshopsp.network.packets.S2CShopDataPacket;
import com.enviouse.futureshopsp.network.packets.S2CVerifyCartResponsePacket;
import com.enviouse.futureshopsp.server.shop.ShopResultCode;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class ShopClientPacketHandler {

    private ShopClientPacketHandler() {
    }

    /**
     * Applies the full shop catalog + balance state received from the server.
     *
     * <p>When {@code packet.forceOpen()} is true (the explicit open path), the GUI is opened if
     * it is not already showing. When false (stock refresh, post-transaction sync, admin reload),
     * the packet only updates an already-open ShopMainScreen and is otherwise discarded — so a
     * player who closed the shop but still has a live server-side session does not get the GUI
     * forced back open by background server activity.
     */
    public static void handleShopData(S2CShopDataPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (!ShopClientState.acceptsSnapshot(packet.shopId(), packet.snapshotRevision())) {
                return;
            }
            boolean shopScreenOpen = mc.screen instanceof ShopMainScreen;
            if (!packet.forceOpen() && !shopScreenOpen) {
                // Silent refresh and the player isn't viewing the shop — nothing to do.
                return;
            }

            ShopClientState.applyShopData(
                    packet.shopId(),
                    packet.balanceMinorUnits(),
                    packet.currencyName(),
                    packet.currencyDecimals(),
                    packet.categories(),
                    packet.items(),
                    packet.promos(),
                    packet.barterRecipes(),
                    packet.adminShopEnabled(),
                    packet.nearbyShops(),
                    packet.balanceAvailable(),
                    packet.providerId(),
                    packet.providerLifecycle(),
                    packet.providerDiagnostic(),
                    packet.snapshotRevision());
            ShopPackets.sendToServer(new com.enviouse.futureshopsp.network.packets.C2SInventorySyncPacket(packet.shopId()));
            if (shopScreenOpen) {
                // Update in-place — preserves nearbyMode, scroll, tabs.
                ((ShopMainScreen) mc.screen).refreshAfterDataUpdate();
            } else {
                mc.setScreen(new ShopMainScreen());
            }
        });
    }

    public static void handleInventorySync(S2CInventorySyncPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (!packet.shopId().equals(ShopClientState.getActiveShopId())) {
                return;
            }
            ShopClientState.applyOwnedCounts(packet.itemCounts());
        });
    }

    public static void handleBalanceUi(S2CBalanceUiPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new BalanceOverviewScreen(
                packet.playerUuid(),
                packet.playerName(),
                packet.balanceMinorUnits(),
                packet.currencyName(),
                packet.currencyDecimals(),
                packet.totalRevenueMinor(),
                packet.pendingSettlementMinor(),
                packet.shopCount(),
                packet.listingCount(),
                packet.totalStock(),
                packet.lowSupplyCount(),
                packet.shopSummaries(),
                packet.alerts(),
                packet.balanceAvailable(),
                packet.providerId(),
                packet.providerLifecycle(),
                packet.providerDiagnostic())));
    }

    public static void handleBalTopUi(S2CBalTopUiPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof BalTopOverviewScreen screen) {
                screen.updatePage(
                        packet.page(),
                        packet.totalPages(),
                        packet.entries(),
                        packet.activityLeaderUuid(),
                        packet.activityLeaderName(),
                        packet.activityLeaderCount(),
                        packet.topSellerUuid(),
                        packet.topSellerName(),
                        packet.topSellerCount(),
                        packet.popularItemId(),
                        packet.popularItemTrades(),
                        packet.popularItemQuantity(),
                        packet.franchises(),
                        packet.rankingAvailable(),
                        packet.providerId(),
                        packet.providerLifecycle(),
                        packet.providerDiagnostic());
                return;
            }
            mc.setScreen(new BalTopOverviewScreen(
                    packet.page(),
                    packet.totalPages(),
                    packet.entries(),
                    packet.currencyName(),
                    packet.currencyDecimals(),
                    packet.activityLeaderUuid(),
                    packet.activityLeaderName(),
                    packet.activityLeaderCount(),
                    packet.topSellerUuid(),
                    packet.topSellerName(),
                    packet.topSellerCount(),
                    packet.popularItemId(),
                    packet.popularItemTrades(),
                    packet.popularItemQuantity(),
                    packet.franchises(),
                    packet.rankingAvailable(),
                    packet.providerId(),
                    packet.providerLifecycle(),
                    packet.providerDiagnostic()));
        });
    }

    public static void handleHistoryResponse(S2CHistoryResponsePacket packet) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (!packet.shopId().equals(ShopClientState.getActiveShopId())) {
                return;
            }
            if (mc.screen instanceof TransactionHistoryScreen historyScreen) {
                if (!historyScreen.applyHistoryResponse(packet.page(), packet.totalPages(), packet.filter())) {
                    return;
                }
            }
            ShopClientState.applyHistoryPage(packet.entries());
        });
    }

    public static void handlePlayerShopData(S2CPlayerShopDataPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            PlayerShopClientState.apply(
                    packet.shopPos(),
                    packet.owner(),
                    packet.ownerUuid(),
                    packet.ownerName(),
                    packet.listings(),
                    packet.linked(),
                    packet.pendingSettlementMinor(),
                    packet.lifetimeRevenueMinor(),
                    packet.recentRevenueRows(),
                    packet.shopName(),
                    packet.singleItemMode(),
                    packet.barterStorageSame(),
                    packet.description(),
                    packet.franchiseName(),
                    packet.placedByCreative(),
                    packet.adminShopMode());
            if (!(mc.screen instanceof PlayerShopBlockScreen)) {
                // Pass current screen as parent for back-button navigation (Items 4, 9)
                mc.setScreen(new PlayerShopBlockScreen(mc.screen));
            }
        });
    }

    public static void handlePlayerShopResult(S2CPlayerShopResultPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            PlayerShopClientState.setResultCode(packet.code());

            // Update ConfirmationModal on open screens
            boolean isSuccess = packet.success();
            String msg = packet.chatMessage() != null && !packet.chatMessage().isBlank() ? packet.chatMessage() : packet.code();
            if (mc.screen instanceof PlayerShopBlockScreen psScreen) {
                psScreen.onTransactionResult(isSuccess, msg);
            } else if (mc.screen instanceof PlayerShopBarterScreen barterScreen) {
                barterScreen.onTransactionResult(isSuccess, msg);
            }

            // Item 18: On barter/storage failure, close the UI and show a chat message
            String code = packet.code();
            if (!packet.success() && ("STORAGE_FULL".equals(code) || "ROLLBACK".equals(code) || "MISSING_BARTER_ITEMS".equals(code))) {
                if (mc.screen instanceof ShopScreenMarker) {
                    mc.setScreen(null);
                }
                if (mc.player != null && packet.chatMessage() != null && !packet.chatMessage().isBlank()) {
                    mc.player.sendSystemMessage(Component.literal(packet.chatMessage()));
                }
            }
        });
    }

    public static void handleSettlementHistory(S2CSettlementHistoryPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (!packet.shopPos().equals(PlayerShopClientState.shopPos())) {
                return;
            }
            PlayerShopClientState.applySettlementHistory(packet.page(), packet.totalPages(), packet.rows());
        });
    }

    public static void handleBuyResponse(S2CBuyResponsePacket packet) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            ShopClientState.recordResponse(packet.snapshotRevision(), packet.responseReason());
            if (packet.balanceAvailable()) {
                ShopClientState.setCurrentBalanceMinorUnits(packet.resultingBalanceMinorUnits());
            } else {
                ShopClientState.setBalanceUnavailable();
            }
            ShopClientState.setStatus(buildBuyMessage(packet), packet.success());

            if (packet.success() && packet.cartCheckout()) {
                ShopClientState.clearCart();
            }

            // Update ConfirmationModal if ItemDetailScreen is open
            if (mc.screen instanceof ItemDetailScreen detailScreen) {
                detailScreen.onTransactionResult(packet.success(), buildBuyMessage(packet).getString());
            } else if (mc.screen instanceof CartScreen cartScreen) {
                cartScreen.onTransactionResult(packet.success(), buildBuyMessage(packet).getString());
            }
        });
    }

    public static void handleSellResponse(S2CSellResponsePacket packet) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            ShopClientState.recordResponse(packet.snapshotRevision(), packet.responseReason());
            if (packet.balanceAvailable()) {
                ShopClientState.setCurrentBalanceMinorUnits(packet.resultingBalanceMinorUnits());
            } else {
                ShopClientState.setBalanceUnavailable();
            }
            ShopClientState.setStatus(buildSellMessage(packet), packet.success());

            // Update ConfirmationModal if ItemDetailScreen is open
            if (mc.screen instanceof ItemDetailScreen detailScreen) {
                detailScreen.onTransactionResult(packet.success(), buildSellMessage(packet).getString());
            }
        });
    }

    public static void handleBarterResponse(S2CBarterResponsePacket packet) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            ShopClientState.setStatus(buildBarterMessage(packet), packet.success());

            // Update ConfirmationModal if BarterScreen is open
            if (mc.screen instanceof BarterScreen barterScreen) {
                barterScreen.onTransactionResult(packet.success(), buildBarterMessage(packet).getString());
            }
        });
    }

    /**
     * Handles a server-initiated force-close.
     * Resets client state and closes any open shop screen.
     */
    public static void handleForceClose(S2CForceClosePacket ignoredPacket) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            ShopClientState.reset();
            PlayerShopCartState.clear(); // Item 34: Clear player shop cart on disconnect
            if (mc.screen instanceof ShopScreenMarker) {
                mc.setScreen(null);
            }
        });
    }

    /**
     * Handles aggregated local shop data for the franchise/owner browsing UI.
     */
    public static void handleLocalShops(S2CLocalShopsPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            ShopClientState.applyLocalShops(packet.owners());
            if (mc.screen instanceof ShopMainScreen existing) {
                existing.refreshAfterDataUpdate();
            }
        });
    }

    /**
     * Handles franchise data — opens or updates the FranchiseManagementScreen.
     */
    public static void handleFranchiseData(S2CFranchiseDataPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof FranchiseManagementScreen screen) {
                screen.updateData(packet.inFranchise(), packet.franchiseId(), packet.franchiseName(),
                        packet.isLeader(), packet.members(), packet.hasPendingInvite(), packet.pendingFranchiseName());
            } else {
                mc.setScreen(new FranchiseManagementScreen(
                        packet.inFranchise(), packet.franchiseId(), packet.franchiseName(),
                        packet.isLeader(), packet.members(), packet.hasPendingInvite(), packet.pendingFranchiseName()));
            }
        });
    }

    /**
     * Handles cart verification response — stores warnings in client state
     * for the cart screen to display.
     */
    public static void handleCartVerification(S2CVerifyCartResponsePacket packet) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> ShopClientState.applyCartVerification(packet.allOk(), packet.warnings()));
    }

    private static Component buildBuyMessage(S2CBuyResponsePacket packet) {
        if (packet.success()) {
            return Component.translatable(
                    packet.cartCheckout()
                            ? "gui.futureshops.status.buy.cart.success"
                            : "gui.futureshops.status.buy.single.success",
                    packet.totalQuantity(),
                    ShopUiUtil.formatMinorUnits(packet.totalMinorUnits()));
        }
        return Component.translatable(errorKey(packet.errorCode()));
    }

    private static Component buildSellMessage(S2CSellResponsePacket packet) {
        if (packet.success()) {
            return Component.translatable(
                    "gui.futureshops.status.sell.success",
                    packet.quantity(),
                    ShopUiUtil.formatMinorUnits(packet.totalMinorUnits()));
        }
        return Component.translatable(errorKey(packet.errorCode()));
    }

    private static Component buildBarterMessage(S2CBarterResponsePacket packet) {
        if (packet.success()) {
            return Component.translatable(
                    "gui.futureshops.status.barter.success",
                    packet.outputQuantity(),
                    packet.multiplier());
        }
        return Component.translatable(errorKey(packet.errorCode()));
    }

    /**
     * Maps a typed server result code to the chat-log lang key used for buy/sell/barter
     * transaction feedback. The switch is exhaustive over {@link ShopResultCode} so adding
     * a new constant without a matching case is a compile error (or, for codes that don't
     * apply to transaction chat, an intentional fall-through to the generic server-error
     * key via the {@code default} branch).
     */
    private static String errorKey(ShopResultCode code) {
        return switch (code) {
            case OUT_OF_STOCK -> "command.futureshops.buy.error.out_of_stock";
            case INVENTORY_FULL -> "command.futureshops.buy.error.inventory_full";
            case SHOP_CLOSED -> "command.futureshops.buy.error.shop_closed";
            case COOLDOWN -> "command.futureshops.buy.error.cooldown";
            case INVALID_ITEM, INVALID_RECIPE -> "command.futureshops.buy.error.invalid_item";
            case INSUFFICIENT_FUNDS -> "command.futureshops.error.insufficient_funds";
            case INVALID_AMOUNT -> "command.futureshops.error.invalid_amount";
            case MAX_BALANCE_EXCEEDED -> "command.futureshops.error.max_balance_exceeded";
            case MISSING_ITEMS -> "gui.futureshops.status.sell.error.missing_items";
            case MISSING_INGREDIENTS -> "gui.futureshops.status.barter.error.missing_ingredients";
            // Codes not specific to transaction chat — fall back to the generic server-error
            // line so nothing ever renders the raw enum name to the player.
            case OK, BOUGHT, SOLD, CONFIG_SAVED, CONFIG_COPIED, DEPARTMENT_SET, PROMO_SET, PROMO_CLEARED,
                 LINKED, BARTER_LINKED, LINK_PENDING, LINK_NONE, BARTER_LINK_PENDING,
                 DESC_PENDING, LISTING_DESC_PENDING, NOT_OWNER, HOLD_ITEM, LISTING_LIMIT,
                 NO_LISTING, UNCONFIGURED, NOT_SINGLE_MODE, USE_SET_DEPARTMENT_ACTION,
                 NO_LINK, BAD_LINK_TARGET, RS_NOT_CONTROLLER, STORAGE_FULL,
                 MISSING_BARTER_ITEMS, ROLLBACK, NOTHING_TO_CLAIM, CLAIM_FAILED,
                 PROMO_FAILED, NO_CLIPBOARD, INVALID_REQUEST, INVALID_TARGET, SERVER_ERROR,
                 STALE_REQUEST, CANCELLED_BY_EVENT, SHOP_OUT_OF_MONEY, BUYBACK_CAP_REACHED, RECOVERY_REQUIRED
                    -> "command.futureshops.error.server";
        };
    }
}
