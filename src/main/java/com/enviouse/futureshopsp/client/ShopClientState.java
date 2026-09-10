package com.enviouse.futureshopsp.client;

import com.enviouse.futureshopsp.data.CatalogBarterRecipe;
import com.enviouse.futureshopsp.data.CatalogCategory;
import com.enviouse.futureshopsp.data.CatalogItem;
import com.enviouse.futureshopsp.data.CatalogPromo;
import com.enviouse.futureshopsp.data.LocalShopOwnerEntry;
import com.enviouse.futureshopsp.data.NearbyShopEntry;
import com.enviouse.futureshopsp.data.TransactionHistoryEntry;
import com.enviouse.futureshopsp.network.packets.S2CVerifyCartResponsePacket;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Client-side singleton holding the most recently received shop state.
 * All fields are set exclusively by incoming S2C packets.
 */
public final class ShopClientState {

    private static volatile String activeShopId = "";
    private static volatile long currentBalanceMinorUnits = 0L;
    private static volatile String currencyName = "Coins";
    private static volatile int currencyDecimals = 2;
    private static volatile boolean balanceAvailable = true;
    private static volatile String providerId = "internal";
    private static volatile String providerLifecycle = "READY";
    private static volatile String providerDiagnostic = "";
    private static volatile long snapshotRevision = 0L;
    private static volatile long lastResponseRevision = 0L;
    private static volatile String lastResponseReason = "";

    // Catalog data — set by S2CShopDataPacket.
    private static volatile List<CatalogCategory> catalogCategories = List.of();
    private static volatile List<CatalogItem> catalogItems = List.of();
    private static volatile List<CatalogPromo> catalogPromos = List.of();
    private static volatile List<CatalogBarterRecipe> catalogBarterRecipes = List.of();
    private static volatile List<TransactionHistoryEntry> transactionHistory = List.of();
    private static volatile boolean adminShopEnabled = true;
    private static volatile List<NearbyShopEntry> nearbyShops = List.of();
    private static volatile List<LocalShopOwnerEntry> localShopOwners = List.of();
    // Precomputed department summary strings (avoids per-frame stream+reduce in ShopMainScreen).
    private static volatile Map<UUID, String> localShopDeptSummaries = Map.of();
    private static volatile List<S2CVerifyCartResponsePacket.CartWarning> cartWarnings = List.of();
    private static volatile boolean cartVerified = false;

    // Cart is keyed by listingId (the catalog resolution key), NOT registry itemId — two NBT
    // variants of one base item are distinct cart lines. ownedItemCounts stays keyed by registry
    // itemId (InventorySyncService reports registry-id counts).
    private static final int MAX_CART_LINES = 256;
    private static final int MAX_CART_QUANTITY = 2304;
    private static final Map<String, Integer> cart = new LinkedHashMap<>();
    private static final Map<String, Integer> ownedItemCounts = new HashMap<>();
    private static volatile ShopStatus status = null;

    private ShopClientState() {
    }

    // -------------------------------------------------------------------------
    // Writers
    // -------------------------------------------------------------------------

    public static void applyShopData(String shopId, long balanceMinorUnits, String currency, int decimals,
                                     List<CatalogCategory> categories, List<CatalogItem> items,
                                     List<CatalogPromo> promos, List<CatalogBarterRecipe> barterRecipes,
                                     boolean adminEnabled, List<NearbyShopEntry> nearby,
                                     boolean balanceIsAvailable, String selectedProviderId,
                                     String selectedProviderLifecycle, String selectedProviderDiagnostic) {
        applyShopData(shopId, balanceMinorUnits, currency, decimals, categories, items, promos, barterRecipes,
                adminEnabled, nearby, balanceIsAvailable, selectedProviderId, selectedProviderLifecycle,
                selectedProviderDiagnostic, 0L);
    }

    public static void applyShopData(String shopId, long balanceMinorUnits, String currency, int decimals,
                                     List<CatalogCategory> categories, List<CatalogItem> items,
                                     List<CatalogPromo> promos, List<CatalogBarterRecipe> barterRecipes,
                                     boolean adminEnabled, List<NearbyShopEntry> nearby,
                                     boolean balanceIsAvailable, String selectedProviderId,
                                     String selectedProviderLifecycle, String selectedProviderDiagnostic,
                                     long receivedSnapshotRevision) {
        if (!acceptsSnapshot(shopId, receivedSnapshotRevision)) {
            return;
        }
        activeShopId = shopId;
        currentBalanceMinorUnits = balanceMinorUnits;
        currencyName = currency;
        currencyDecimals = decimals;
        balanceAvailable = balanceIsAvailable;
        providerId = selectedProviderId == null || selectedProviderId.isBlank() ? "unknown" : selectedProviderId;
        providerLifecycle = selectedProviderLifecycle == null || selectedProviderLifecycle.isBlank()
                ? "UNRESOLVED" : selectedProviderLifecycle;
        providerDiagnostic = selectedProviderDiagnostic == null ? "" : selectedProviderDiagnostic;
        snapshotRevision = Math.max(0L, receivedSnapshotRevision);
        catalogCategories = List.copyOf(categories);
        catalogItems = List.copyOf(items);
        catalogPromos = List.copyOf(promos);
        catalogBarterRecipes = List.copyOf(barterRecipes);
        adminShopEnabled = adminEnabled;
        nearbyShops = List.copyOf(nearby);
        transactionHistory = List.of();
        synchronized (ownedItemCounts) {
            ownedItemCounts.clear();
        }
        sanitizeCart();
    }

    /**
     * Clears all state.  Called by {@code S2CForceClosePacket} to ensure
     * the client does not display stale catalog data after a forced close.
     */
    public static void reset() {
        activeShopId = "";
        currentBalanceMinorUnits = 0L;
        balanceAvailable = false;
        providerId = "unknown";
        providerLifecycle = "UNRESOLVED";
        providerDiagnostic = "";
        snapshotRevision = 0L;
        lastResponseRevision = 0L;
        lastResponseReason = "";
        catalogCategories = List.of();
        catalogItems = List.of();
        catalogPromos = List.of();
        catalogBarterRecipes = List.of();
        adminShopEnabled = true;
        nearbyShops = List.of();
        transactionHistory = List.of();
        status = null;
        synchronized (cart) {
            cart.clear();
        }
        synchronized (ownedItemCounts) {
            ownedItemCounts.clear();
        }
    }

    public static void setCurrentBalanceMinorUnits(long balanceMinorUnits) {
        currentBalanceMinorUnits = balanceMinorUnits;
        balanceAvailable = true;
    }

    public static void setBalanceUnavailable() {
        balanceAvailable = false;
    }

    public static long getSnapshotRevision() {
        return snapshotRevision;
    }

    public static boolean acceptsSnapshot(String shopId, long receivedSnapshotRevision) {
        return shopId != null && (!shopId.equals(activeShopId) || receivedSnapshotRevision >= snapshotRevision);
    }

    public static void recordResponse(long serverRevision, String reason) {
        lastResponseRevision = Math.max(0L, serverRevision);
        lastResponseReason = reason == null ? "" : reason;
    }

    public static long getLastResponseRevision() {
        return lastResponseRevision;
    }

    public static String getLastResponseReason() {
        return lastResponseReason;
    }

    public static boolean isBalanceAvailable() {
        return balanceAvailable;
    }

    public static String getProviderId() {
        return providerId;
    }

    public static String getProviderLifecycle() {
        return providerLifecycle;
    }

    public static String getProviderDiagnostic() {
        return providerDiagnostic;
    }

    public static void addToCart(String listingId, int quantity) {
        if (listingId == null || listingId.isBlank() || quantity <= 0) {
            return;
        }

        synchronized (cart) {
            boolean existing = cart.containsKey(listingId);
            if (!existing && cart.size() >= MAX_CART_LINES) {
                return;
            }
            int newQuantity = saturatingAddInt(cart.getOrDefault(listingId, 0), quantity);
            int clamped = clampCartQuantity(listingId, newQuantity);
            if (clamped <= 0) {
                cart.remove(listingId);
            } else {
                cart.put(listingId, clamped);
            }
            sanitizeCartLocked();
        }
    }

    public static void setCartQuantity(String listingId, int quantity) {
        if (listingId == null || listingId.isBlank()) {
            return;
        }
        synchronized (cart) {
            if (quantity <= 0) {
                cart.remove(listingId);
            } else if (!cart.containsKey(listingId) && cart.size() >= MAX_CART_LINES) {
                return;
            } else {
                cart.put(listingId, clampCartQuantity(listingId, quantity));
            }
            sanitizeCartLocked();
        }
    }

    public static void removeFromCart(String listingId) {
        synchronized (cart) {
            cart.remove(listingId);
        }
    }

    public static void clearCart() {
        synchronized (cart) {
            cart.clear();
        }
    }

    // -------------------------------------------------------------------------
    // Readers
    // -------------------------------------------------------------------------

    public static String getActiveShopId() {
        return activeShopId;
    }

    public static long getCurrentBalanceMinorUnits() {
        return currentBalanceMinorUnits;
    }

    public static String getCurrencyName() {
        return currencyName;
    }

    public static int getCurrencyDecimals() {
        return currencyDecimals;
    }

    public static List<CatalogCategory> getCatalogCategories() {
        return catalogCategories;
    }

    public static List<CatalogItem> getCatalogItems() {
        return catalogItems;
    }

    public static List<CatalogPromo> getCatalogPromos() {
        return catalogPromos;
    }

    public static List<CatalogBarterRecipe> getCatalogBarterRecipes() {
        return catalogBarterRecipes;
    }

    public static List<TransactionHistoryEntry> getTransactionHistory() {
        return transactionHistory;
    }

    public static boolean isAdminShopEnabled() {
        return adminShopEnabled;
    }

    public static List<NearbyShopEntry> getNearbyShops() {
        return nearbyShops;
    }

    public static List<CatalogBarterRecipe> getBarterRecipesForItem(String itemId) {
        return catalogBarterRecipes.stream().filter(recipe -> recipe.targetItemId().equals(itemId)).toList();
    }

    /** Resolves a catalog row by its listingId (the cart/buy/sell key), NOT its registry itemId. */
    public static Optional<CatalogItem> getCatalogItem(String listingId) {
        return catalogItems.stream().filter(item -> item.listingId().equals(listingId)).findFirst();
    }

    public static int getOwnedCount(String itemId) {
        synchronized (ownedItemCounts) {
            return ownedItemCounts.getOrDefault(itemId, 0);
        }
    }

    public static void applyOwnedCounts(Map<String, Integer> counts) {
        synchronized (ownedItemCounts) {
            ownedItemCounts.clear();
            ownedItemCounts.putAll(counts);
        }
    }

    public static void applyHistoryPage(List<TransactionHistoryEntry> entries) {
        transactionHistory = List.copyOf(entries);
    }

    public static List<CartEntry> getCartEntries() {
        synchronized (cart) {
            return cart.entrySet().stream()
                    .map(entry -> new CartEntry(entry.getKey(), entry.getValue()))
                    .toList();
        }
    }

    public static int getCartLineCount() {
        synchronized (cart) {
            return cart.size();
        }
    }

    public static int getCartTotalQuantity() {
        synchronized (cart) {
            int total = 0;
            for (int quantity : cart.values()) {
                total = saturatingAddInt(total, quantity);
            }
            return total;
        }
    }

    public static long getCartTotalMinorUnits() {
        synchronized (cart) {
            long total = 0L;
            for (Map.Entry<String, Integer> entry : cart.entrySet()) {
                CatalogItem item = getCatalogItem(entry.getKey()).orElse(null);
                if (item == null) {
                    continue;
                }
                long unitPrice = item.hasPromo() ? item.promoPrice() : item.buyPrice();
                total = saturatingAdd(total, saturatingMultiply(unitPrice, entry.getValue()));
            }
            return total;
        }
    }

    public static void setStatus(Component message, boolean success) {
        status = new ShopStatus(message, success, System.currentTimeMillis() + 4500L);
    }

    public static ShopStatus getStatus() {
        ShopStatus current = status;
        if (current != null && current.expiresAtMillis() < System.currentTimeMillis()) {
            status = null;
            return null;
        }
        return current;
    }

    public static void clearStatus() {
        status = null;
    }

    private static void sanitizeCart() {
        synchronized (cart) {
            sanitizeCartLocked();
        }
    }

    private static void sanitizeCartLocked() {
        cart.entrySet().removeIf(entry -> {
            CatalogItem item = getCatalogItem(entry.getKey()).orElse(null);
            if (item == null || item.buyPrice() <= 0L) {
                return true;
            }

            int clamped = clampCartQuantity(item.listingId(), entry.getValue());
            if (clamped <= 0) {
                return true;
            }
            entry.setValue(clamped);
            return false;
        });
    }

    private static int clampCartQuantity(String listingId, int quantity) {
        CatalogItem item = getCatalogItem(listingId).orElse(null);
        if (item == null) {
            return 0;
        }

        int clamped = Math.max(1, Math.min(MAX_CART_QUANTITY, quantity));
        if (!item.unlimited()) {
            clamped = Math.min(clamped, item.stock());
        }
        return clamped;
    }

    private static int saturatingAddInt(int left, int right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return right < 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        }
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return left < 0L ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private static long saturatingMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException ignored) {
            return (left < 0L) == (right < 0L) ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    /** {@code listingId} is the catalog resolution key for this cart line (see {@link CatalogItem}). */
    public record CartEntry(String listingId, int quantity) {
    }

    public record ShopStatus(Component message, boolean success, long expiresAtMillis) {
    }

    // -------------------------------------------------------------------------
    // Local shops aggregation
    // -------------------------------------------------------------------------

    public static void applyLocalShops(List<LocalShopOwnerEntry> owners) {
        localShopOwners = List.copyOf(owners);
        // Rebuild the department-summary cache once per data update.
        Map<UUID, String> summaries = new HashMap<>(owners.size() * 2);
        for (LocalShopOwnerEntry owner : owners) {
            if (owner.departments().isEmpty()) {
                summaries.put(owner.ownerUuid(), "No departments");
                continue;
            }
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (LocalShopOwnerEntry.LocalDepartment dept : owner.departments()) {
                if (!first) sb.append(", ");
                sb.append(dept.name());
                first = false;
            }
            summaries.put(owner.ownerUuid(), sb.toString());
        }
        localShopDeptSummaries = Map.copyOf(summaries);
    }

    public static List<LocalShopOwnerEntry> getLocalShopOwners() {
        return localShopOwners;
    }

    /** Returns the cached comma-joined department-name summary for {@code ownerUuid}. */
    public static String getLocalShopDeptSummary(UUID ownerUuid) {
        String cached = localShopDeptSummaries.get(ownerUuid);
        return cached != null ? cached : "No departments";
    }

    // -------------------------------------------------------------------------
    // Cart verification
    // -------------------------------------------------------------------------

    public static void applyCartVerification(boolean allOk, List<S2CVerifyCartResponsePacket.CartWarning> warnings) {
        cartVerified = true;
        cartWarnings = List.copyOf(warnings);
    }

    public static List<S2CVerifyCartResponsePacket.CartWarning> getCartWarnings() {
        return cartWarnings;
    }

    public static boolean isCartVerified() {
        return cartVerified;
    }

    public static void clearCartVerification() {
        cartVerified = false;
        cartWarnings = List.of();
    }
}
