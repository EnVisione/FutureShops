package com.enviouse.futureshopsp.server.session;

import com.enviouse.futureshopsp.event.ShopCloseEvent;
import com.enviouse.futureshopsp.network.ShopPackets;
import com.enviouse.futureshopsp.network.packets.S2CForceClosePacket;
import com.enviouse.futureshopsp.server.transaction.TransactionHistoryService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ShopSessionManager {

    private static final Map<UUID, ShopSession> SESSIONS = new ConcurrentHashMap<>();

    private ShopSessionManager() {
    }

    // -------------------------------------------------------------------------
    // Open
    // -------------------------------------------------------------------------

    /** Opens a session without a block-position constraint (command-opened shop). */
    public static ShopSession open(UUID playerUUID, String shopId) {
        return open(playerUUID, shopId, null);
    }

    /** Opens a session anchored to a specific shop block (for distance auto-close). */
    public static ShopSession open(UUID playerUUID, String shopId, BlockPos shopBlockPos) {
        ShopSession session = new ShopSession(playerUUID, shopId, shopBlockPos, System.currentTimeMillis());
        SESSIONS.put(playerUUID, session);
        return session;
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    public static Optional<ShopSession> get(UUID playerUUID) {
        return Optional.ofNullable(SESSIONS.get(playerUUID));
    }

    /** Advances the authoritative catalog revision for an active shop session. */
    public static long advanceSnapshotRevision(UUID playerUUID, String shopId) {
        final long[] result = {0L};
        SESSIONS.computeIfPresent(playerUUID, (uuid, current) -> {
            if (!current.shopId().equals(shopId)) {
                result[0] = current.snapshotRevision();
                return current;
            }
            long next = current.snapshotRevision() == Long.MAX_VALUE
                    ? Long.MAX_VALUE : current.snapshotRevision() + 1L;
            result[0] = next;
            return current.withSnapshotRevision(next);
        });
        return result[0];
    }

    public static Map<UUID, ShopSession> snapshotSessions() {
        return Map.copyOf(new HashMap<>(SESSIONS));
    }

    // -------------------------------------------------------------------------
    // Close
    // -------------------------------------------------------------------------

    /** Silently closes the session without notifying the client (e.g. on logout). */
    public static void close(UUID playerUUID) {
        SESSIONS.remove(playerUUID);
        TransactionHistoryService.clearSubscription(playerUUID);
    }

    /**
     * Closes the session AND sends an {@link S2CForceClosePacket} to the player
     * so the client can dismiss its open shop screen.
     */
    public static void closeAndForceClose(ServerPlayer player, String reason) {
        ShopSession session = SESSIONS.remove(player.getUUID());
        if (session != null) {
            TransactionHistoryService.clearSubscription(player.getUUID());
            ShopPackets.sendToPlayer(player, new S2CForceClosePacket(reason));
            // Fire informational ShopCloseEvent (spec §33)
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new ShopCloseEvent(player.getUUID(), session.shopId(), reason));
        }
    }

    /**
     * Closes every active session and sends {@link S2CForceClosePacket} to all
     * affected online players.  Used at server shutdown.
     */
    public static void closeAllAndForceClose(MinecraftServer server, String reason) {
        // Snapshot sessions before clearing so we can fire ShopCloseEvent for each
        Map<UUID, ShopSession> snapshot = new HashMap<>(SESSIONS);
        SESSIONS.clear();
        for (Map.Entry<UUID, ShopSession> entry : snapshot.entrySet()) {
            UUID uuid = entry.getKey();
            ShopSession session = entry.getValue();
            TransactionHistoryService.clearSubscription(uuid);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                ShopPackets.sendToPlayer(player, new S2CForceClosePacket(reason));
            }
            // Fire informational ShopCloseEvent (spec §33) for each session
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new ShopCloseEvent(uuid, session.shopId(), reason));
        }
    }

    /** Clears all sessions without sending any packets (used only in tests / hard reset). */
    public static void clear() {
        Set<UUID> keys = new HashSet<>(SESSIONS.keySet());
        SESSIONS.clear();
        keys.forEach(TransactionHistoryService::clearSubscription);
    }
}
