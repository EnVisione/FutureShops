package com.enviouse.futureshopsp.command;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import com.enviouse.futureshopsp.server.economy.BalanceManager;
import com.enviouse.futureshopsp.api.economy.BalanceSnapshot;
import com.enviouse.futureshopsp.api.economy.ProviderError;
import com.enviouse.futureshopsp.api.economy.ProviderResult;

import java.math.BigDecimal;
import java.util.UUID;

public final class EconomyCommandUtil {
    private EconomyCommandUtil() {
    }

    public static long parseAmountToMinorUnits(String raw, int decimals) {
        try {
            BigDecimal parsed = new BigDecimal(raw.trim());
            if (parsed.signum() <= 0) {
                throw new IllegalArgumentException("NON_POSITIVE");
            }

            BigDecimal scaled = parsed.movePointRight(decimals);
            if (scaled.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException("TOO_MANY_DECIMALS");
            }

            return scaled.longValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new IllegalArgumentException("INVALID_NUMBER", ex);
        }
    }

    public static String formatMinorUnits(long value, int decimals) {
        if (decimals <= 0) {
            return Long.toString(value);
        }
        return BigDecimal.valueOf(value, decimals).toPlainString();
    }

    /**
     * Formats a confirmed post mutation balance without treating an unavailable query as zero.
     */
    public static Component formatResultingBalance(ProviderResult<?> mutation, UUID playerUUID, int decimals) {
        if (mutation != null && mutation.receipt().isPresent()) {
            var receipt = mutation.receipt().orElseThrow();
            if (receipt.resultingBalanceMinorUnits().isPresent()) {
                return Component.literal(formatMinorUnits(receipt.resultingBalanceMinorUnits().getAsLong(), decimals));
            }
        }
        ProviderResult<BalanceSnapshot> balance = BalanceManager.queryBalance(playerUUID);
        if (balance.confirmed()) {
            return Component.literal(formatMinorUnits(
                    balance.value().orElseThrow().balanceMinorUnits(), decimals));
        }
        return Component.translatable("gui.futureshops.economy.balance_unavailable");
    }

    /**
     * Maps a server {@link com.enviouse.futureshopsp.server.shop.ShopResultCode} to the
     * chat-log lang key used for direct economy commands (/pay, /bal, etc.).
     *
     * <p>The switch is exhaustive — every enum constant must appear as a case label so
     * that adding a new code is a compile error until an intentional mapping (specific
     * or generic) is chosen. This prevents the silent-fall-through to the generic
     * {@code command.futureshops.error.server} line that a {@code default ->} branch
     * would allow.
     */
    public static void sendProviderError(ServerPlayer player, com.enviouse.futureshopsp.server.shop.ShopResultCode errorCode) {
        String key = switch (errorCode) {
            case INVALID_AMOUNT -> "command.futureshops.error.invalid_amount";
            case INSUFFICIENT_FUNDS -> "command.futureshops.error.insufficient_funds";
            case MAX_BALANCE_EXCEEDED -> "command.futureshops.error.max_balance_exceeded";
            case INVALID_TARGET -> "command.futureshops.pay.self";
            // Codes not meaningful for direct economy commands fall through to a single
            // generic server-error line. The explicit enumeration is a deliberate
            // "intentionally generic" contract — see the exhaustive-switch unit test.
            case OK, BOUGHT, SOLD, CONFIG_SAVED, CONFIG_COPIED, DEPARTMENT_SET, PROMO_SET, PROMO_CLEARED,
                 LINKED, BARTER_LINKED, LINK_PENDING, LINK_NONE, BARTER_LINK_PENDING,
                 DESC_PENDING, LISTING_DESC_PENDING, NOT_OWNER, HOLD_ITEM, LISTING_LIMIT,
                 NO_LISTING, INVALID_ITEM, UNCONFIGURED, NOT_SINGLE_MODE,
                 USE_SET_DEPARTMENT_ACTION, NO_LINK, BAD_LINK_TARGET, RS_NOT_CONTROLLER,
                 OUT_OF_STOCK, STORAGE_FULL, INVENTORY_FULL, MISSING_BARTER_ITEMS,
                 MISSING_INGREDIENTS, MISSING_ITEMS, INVALID_RECIPE, ROLLBACK,
                 NOTHING_TO_CLAIM, CLAIM_FAILED, PROMO_FAILED, NO_CLIPBOARD, INVALID_REQUEST,
                 SERVER_ERROR, CANCELLED_BY_EVENT, COOLDOWN, SHOP_CLOSED,
                 STALE_REQUEST,
                 SHOP_OUT_OF_MONEY, BUYBACK_CAP_REACHED, RECOVERY_REQUIRED
                    -> "command.futureshops.error.server";
        };

        player.sendSystemMessage(error(Component.translatable(key)));
    }

    public static void sendProviderError(ServerPlayer player, ProviderResult<?> result) {
        if (result == null || result.confirmed()) {
            return;
        }
        String key = switch (result.error()) {
            case INVALID_AMOUNT -> "command.futureshops.error.invalid_amount";
            case INSUFFICIENT_FUNDS -> "command.futureshops.error.insufficient_funds";
            case NONE, INVALID_REQUEST, CAPABILITY_MISSING, NOT_READY, INCOMPATIBLE,
                 PERMISSION_DENIED, DUPLICATE_REQUEST, RECEIPT_NOT_FOUND, PROVIDER_EXCEPTION,
                 TIMEOUT, REQUEST_CONFLICT, BINDING_CHANGED, INVALID_PRECISION, UNKNOWN
                    -> "command.futureshops.economy.unavailable";
        };
        player.sendSystemMessage(error(Component.translatable(key)));
    }

    public static MutableComponent info(Component component) {
        return component.copy().withStyle(ChatFormatting.GRAY);
    }

    public static MutableComponent success(Component component) {
        return component.copy().withStyle(ChatFormatting.GREEN);
    }

    public static MutableComponent warning(Component component) {
        return component.copy().withStyle(ChatFormatting.GOLD);
    }

    public static MutableComponent error(Component component) {
        return component.copy().withStyle(ChatFormatting.RED);
    }
}
