package com.enviouse.futureshopsp.client;

import com.enviouse.futureshopsp.data.CatalogItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ShopClientStateTest {

    @AfterEach
    void clearState() {
        ShopClientState.reset();
    }

    @Test
    void cartQuantityAndLinesAreBounded() {
        List<CatalogItem> items = new ArrayList<>();
        CatalogItem item = item("minecraft:stone", 1L);
        items.add(item);
        for (int i = 1; i <= 256; i++) {
            items.add(item("minecraft:stone_" + i, 1L));
        }
        ShopClientState.applyShopData("shop", 0L, "Coins", 2, List.of(), items, List.of(), List.of(),
                true, List.of(), true, "internal", "READY", "");

        ShopClientState.addToCart(item.listingId(), Integer.MAX_VALUE);
        assertEquals(2304, ShopClientState.getCartEntries().getFirst().quantity());

        for (int i = 1; i <= 256; i++) {
            ShopClientState.addToCart("minecraft:stone_" + i, 1);
        }
        assertEquals(256, ShopClientState.getCartLineCount());
    }

    @Test
    void totalsSaturateInsteadOfWrapping() {
        CatalogItem item = item("minecraft:stone", Long.MAX_VALUE);
        ShopClientState.applyShopData("shop", 0L, "Coins", 2, List.of(), List.of(item), List.of(), List.of(),
                true, List.of(), true, "internal", "READY", "");
        ShopClientState.addToCart(item.listingId(), Integer.MAX_VALUE);

        assertEquals(Long.MAX_VALUE, ShopClientState.getCartTotalMinorUnits());
        assertEquals(2304, ShopClientState.getCartTotalQuantity());
    }

    @Test
    void unavailableBalanceNeverSubstitutesZero() {
        ShopClientState.applyShopData("shop", 12345L, "Coins", 2, List.of(), List.of(), List.of(), List.of(),
                true, List.of(), true, "internal", "READY", "");

        ShopClientState.setBalanceUnavailable();

        assertFalse(ShopClientState.isBalanceAvailable());
        assertEquals(12345L, ShopClientState.getCurrentBalanceMinorUnits());
    }

    @Test
    void olderShopSnapshotIsRejectedAfterNewerRevisionArrives() {
        ShopClientState.applyShopData("shop", 0L, "Coins", 2, List.of(), List.of(), List.of(), List.of(),
                true, List.of(), true, "internal", "READY", "", 8L);

        assertEquals(8L, ShopClientState.getSnapshotRevision());
        assertFalse(ShopClientState.acceptsSnapshot("shop", 7L));
        assertEquals(8L, ShopClientState.getSnapshotRevision());
        ShopClientState.applyShopData("shop", 0L, "Coins", 2, List.of(), List.of(), List.of(), List.of(),
                true, List.of(), true, "internal", "READY", "", 9L);
        assertEquals(9L, ShopClientState.getSnapshotRevision());
        ShopClientState.recordResponse(9L, "stale_snapshot");
        assertEquals(9L, ShopClientState.getLastResponseRevision());
        assertEquals("stale_snapshot", ShopClientState.getLastResponseReason());
    }

    private static CatalogItem item(String listingId, long buyPrice) {
        return new CatalogItem(listingId, listingId, listingId, buyPrice, 1L, -1, true, false,
                "all", false, 0L, false, "");
    }
}
