package com.enviouse.futureshopsp.server.shop;

import com.enviouse.futureshopsp.catalog.ShopCatalog;
import com.enviouse.futureshopsp.catalog.ShopDefinition;
import com.enviouse.futureshopsp.data.NearbyShopEntry;
import com.enviouse.futureshopsp.event.ShopOpenEvent;
import com.enviouse.futureshopsp.network.ShopPackets;
import com.enviouse.futureshopsp.network.packets.S2CShopDataPacket;
import com.enviouse.futureshopsp.server.economy.BalanceManager;
import com.enviouse.futureshopsp.api.economy.BalanceSnapshot;
import com.enviouse.futureshopsp.api.economy.ProviderResult;
import com.enviouse.futureshopsp.server.session.ShopSession;
import com.enviouse.futureshopsp.server.session.ShopSessionManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared authoritative helper for assembling and sending shop data packets.
 * Centralizes the logic used by commands, open-shop packets, reload handling, and buy refreshes.
 */
public final class ShopDataService {
    private ShopDataService() {
    }

    public static String resolveShopId(String requestedShopId) {
        String raw = requestedShopId == null || requestedShopId.isBlank() ? "default" : requestedShopId;
        return ShopCatalog.getOrDefault(raw).map(ShopDefinition::shopId).orElse("default");
    }

    public static void openShop(ServerPlayer player, String requestedShopId) {
        String shopId = resolveShopId(requestedShopId);

        // Fire cancellable ShopOpenEvent (spec §33)
        ShopOpenEvent event = new ShopOpenEvent(player, shopId);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) return;

        ShopSessionManager.open(player.getUUID(), shopId);
        // forceOpen=true: this is the deliberate open path (command / shop block use)
        sendShopData(player, shopId, true, true);
        InventorySyncService.sendOwnedCounts(player, shopId);
    }

    public static void sendShopData(ServerPlayer player, String requestedShopId) {
        sendShopData(player, requestedShopId, true, true);
    }

    public static void sendShopData(ServerPlayer player, String requestedShopId, boolean includeNearbyShops) {
        sendShopData(player, requestedShopId, includeNearbyShops, true);
    }

    /**
     * Sends shop data. When {@code includeNearbyShops} is false, the expensive nearby-shop
     * scan is skipped — use this for post-transaction refreshes where nearby shops haven't changed.
     *
     * <p>{@code forceOpen} controls client-side behavior when no shop screen is currently open:
     * {@code true} pops the shop GUI open (used by the explicit open path); {@code false} silently
     * updates an already-open screen and is otherwise discarded — used for stock refresh, post-
     * transaction sync, and admin reload, so a player who closed the GUI but still has a live
     * session does not get the screen forced back open.
     */
    public static void sendShopData(ServerPlayer player, String requestedShopId, boolean includeNearbyShops, boolean forceOpen) {
        String shopId = resolveShopId(requestedShopId);
        long snapshotRevision = ShopSessionManager.advanceSnapshotRevision(player.getUUID(), shopId);
        String currencyName = BalanceManager.getCurrencyName();
        int decimalPlaces = BalanceManager.getDecimalPlaces();
        ProviderResult<BalanceSnapshot> balanceResult = BalanceManager.queryBalance(player.getUUID());
        long balance = balanceResult.value().map(BalanceSnapshot::balanceMinorUnits).orElse(0L);
        var lifecycle = BalanceManager.getLifecycleSnapshotOrUnresolved();

        // Check admin shop toggle
        boolean adminEnabled = player.getServer() != null
                && AdminShopToggleSavedData.get(player.getServer()).isAdminShopEnabled();

        // Scan nearby player shops only when requested (skipped for post-transaction refreshes)
        List<NearbyShopEntry> nearbyShops = includeNearbyShops && player.getServer() != null
                ? NearbyShopScanner.scanNearby(player, com.enviouse.futureshopsp.Config.localListingsScanRadiusBlocks)
                : List.of();

        ShopPackets.sendToPlayer(player, new S2CShopDataPacket(
                shopId,
                balance,
                currencyName,
                decimalPlaces,
                adminEnabled ? ShopCatalog.buildCategories(shopId, player.getServer()) : List.of(),
                adminEnabled ? ShopCatalog.buildItems(shopId, player.getServer()) : List.of(),
                adminEnabled ? ShopCatalog.buildPromos(shopId) : List.of(),
                adminEnabled ? ShopCatalog.buildBarterRecipes(shopId) : List.of(),
                adminEnabled,
                nearbyShops,
                forceOpen,
                balanceResult.confirmed(),
                lifecycle.providerId(),
                lifecycle.lifecycle().name(),
                lifecycle.diagnostic(),
                snapshotRevision));
    }

    public static void resendActiveSessions(MinecraftServer server) {
        for (Map.Entry<UUID, ShopSession> entry : ShopSessionManager.snapshotSessions().entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }

            ShopSession session = entry.getValue();
            if (ShopCatalog.get(session.shopId()).isPresent()) {
                // forceOpen=false: admin reload should refresh open UIs, not pop them open
                sendShopData(player, session.shopId(), true, false);
            } else {
                ShopSessionManager.closeAndForceClose(player, "SHOP_REMOVED");
            }
        }
    }

    public static void resendSessionsViewingShop(MinecraftServer server, String requestedShopId) {
        String shopId = resolveShopId(requestedShopId);
        for (Map.Entry<UUID, ShopSession> entry : ShopSessionManager.snapshotSessions().entrySet()) {
            if (!entry.getValue().shopId().equals(shopId)) {
                continue;
            }

            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                // Post-transaction / stock-refresh resync — never force the GUI open.
                sendShopData(player, shopId, false, false);
            }
        }
    }
}
