package com.enviouse.futureshopsp.api.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Checked integer minor unit arithmetic shared by provider and transaction boundaries. */
public final class EconomyAmounts {
    private EconomyAmounts() {
    }

    public static long addExact(long left, long right) {
        return Math.addExact(left, right);
    }

    public static long subtractExact(long left, long right) {
        return Math.subtractExact(left, right);
    }

    public static long multiplyExact(long left, long right) {
        return Math.multiplyExact(left, right);
    }

    public static long requirePositive(long amount) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("amount must be positive");
        }
        return amount;
    }

    public static long requireNonNegative(long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        return amount;
    }

    public static long parseDecimal(String value, int decimalPlaces) {
        if (value == null || decimalPlaces < 0 || decimalPlaces > 6) {
            throw new IllegalArgumentException("decimal input is invalid");
        }
        try {
            BigDecimal parsed = new BigDecimal(value.trim());
            BigDecimal scaled = parsed.movePointRight(decimalPlaces);
            return scaled.setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("decimal input is not an exact minor unit value", exception);
        }
    }

    /** Converts a backend raw double only when its finite minor unit value is exact and bounded. */
    public static long fromRawDouble(double value, int decimalPlaces) {
        if (!Double.isFinite(value) || (value == 0.0d && Double.doubleToRawLongBits(value) < 0L)
                || Math.abs(value) > 9007199254740992.0d) {
            throw new IllegalArgumentException("raw double is not an exact supported amount");
        }
        return parseDecimal(BigDecimal.valueOf(value).toPlainString(), decimalPlaces);
    }
}
