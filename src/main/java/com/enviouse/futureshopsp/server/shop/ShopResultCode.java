package com.enviouse.futureshopsp.server.shop;

/**
 * Enumeration of every server→client result code the player-shop system can emit.
 *
 * <p>Before this enum existed, {@code sendResult} took a free-form {@code String} code.
 * Typos went undetected until a runtime test run, and adding a code without a matching
 * lang key silently rendered the key itself as the player-facing text. Using an enum
 * gives us:
 * <ul>
 *   <li>Compile-time verification at every call site.</li>
 *   <li>A single authoritative list of codes — easy to diff with {@code en_us.json}.</li>
 *   <li>A canonical {@link #langKey()} accessor so client formatting stays mechanical.</li>
 * </ul>
 *
 * <p>The wire value is {@link #name()}, preserving backwards compatibility with the
 * existing {@code S2CPlayerShopResultPacket} payload.
 */
public enum ShopResultCode {
    // ── Success / neutral ────────────────────────────────────────────────
    OK,
    BOUGHT,
    SOLD,
    CONFIG_SAVED,
    CONFIG_COPIED,
    DEPARTMENT_SET,
    PROMO_SET,
    PROMO_CLEARED,
    LINKED,
    BARTER_LINKED,

    // ── Pending-state codes (used by two-step flows) ─────────────────────
    LINK_PENDING,
    LINK_NONE,
    BARTER_LINK_PENDING,
    DESC_PENDING,
    LISTING_DESC_PENDING,

    // ── Owner-side permission / input validation ─────────────────────────
    NOT_OWNER,
    HOLD_ITEM,
    LISTING_LIMIT,
    NO_LISTING,
    INVALID_ITEM,
    UNCONFIGURED,
    NOT_SINGLE_MODE,
    USE_SET_DEPARTMENT_ACTION,

    // ── Linking errors ───────────────────────────────────────────────────
    NO_LINK,
    BAD_LINK_TARGET,
    RS_NOT_CONTROLLER,

    // ── Transaction errors ───────────────────────────────────────────────
    OUT_OF_STOCK,
    STORAGE_FULL,
    INVENTORY_FULL,
    INSUFFICIENT_FUNDS,
    SHOP_OUT_OF_MONEY,
    BUYBACK_CAP_REACHED,
    MISSING_BARTER_ITEMS,
    MISSING_INGREDIENTS,
    MISSING_ITEMS,
    INVALID_RECIPE,
    INVALID_AMOUNT,
    MAX_BALANCE_EXCEEDED,
    ROLLBACK,
    NOTHING_TO_CLAIM,
    CLAIM_FAILED,
    RECOVERY_REQUIRED,
    PROMO_FAILED,
    NO_CLIPBOARD,

    // ── Protocol / request errors ────────────────────────────────────────
    INVALID_REQUEST,
    INVALID_TARGET,
    STALE_REQUEST,
    SERVER_ERROR,
    CANCELLED_BY_EVENT,
    COOLDOWN,
    SHOP_CLOSED;

    /** Wire-format code (stable across client/server). */
    public String wire() {
        return name();
    }

    /** Matching client lang key under {@code gui.futureshops.player_shop.result.*}. */
    public String langKey() {
        return "gui.futureshops.player_shop.result." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
