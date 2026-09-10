package com.enviouse.futureshopsp.server.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopSessionRevisionTest {

    @AfterEach
    void clearSessions() {
        ShopSessionManager.clear();
    }

    @Test
    void revisionsAdvanceOnlyForTheMatchingShop() {
        UUID player = UUID.randomUUID();
        ShopSessionManager.open(player, "default");

        assertEquals(1L, ShopSessionManager.advanceSnapshotRevision(player, "default"));
        assertEquals(1L, ShopSessionManager.get(player).orElseThrow().snapshotRevision());
        assertEquals(1L, ShopSessionManager.advanceSnapshotRevision(player, "other"));
        assertEquals(2L, ShopSessionManager.advanceSnapshotRevision(player, "default"));
    }
}
