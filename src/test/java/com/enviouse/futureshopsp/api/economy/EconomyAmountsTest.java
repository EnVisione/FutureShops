package com.enviouse.futureshopsp.api.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EconomyAmountsTest {
    @Test
    void arithmeticUsesCheckedLongOperations() {
        assertEquals(3L, EconomyAmounts.addExact(1L, 2L));
        assertEquals(-1L, EconomyAmounts.subtractExact(1L, 2L));
        assertEquals(6L, EconomyAmounts.multiplyExact(2L, 3L));
        assertThrows(ArithmeticException.class, () -> EconomyAmounts.addExact(Long.MAX_VALUE, 1L));
        assertThrows(ArithmeticException.class, () -> EconomyAmounts.subtractExact(Long.MIN_VALUE, 1L));
        assertThrows(ArithmeticException.class, () -> EconomyAmounts.multiplyExact(Long.MAX_VALUE, 2L));
    }

    @Test
    void decimalParsingRejectsLossyValues() {
        assertEquals(1234L, EconomyAmounts.parseDecimal("12.34", 2));
        assertEquals(-5L, EconomyAmounts.parseDecimal("-0.5", 1));
        assertThrows(IllegalArgumentException.class, () -> EconomyAmounts.parseDecimal("1.001", 2));
        assertThrows(IllegalArgumentException.class, () -> EconomyAmounts.parseDecimal("nan", 2));
    }

    @Test
    void rawDoubleConversionRejectsNonFiniteLossyAndUnsafeValues() {
        assertEquals(125L, EconomyAmounts.fromRawDouble(1.25d, 2));
        assertEquals(9007199254740992L, EconomyAmounts.fromRawDouble(9007199254740992d, 0));
        assertThrows(IllegalArgumentException.class, () -> EconomyAmounts.fromRawDouble(1.5d, 0));
        assertThrows(IllegalArgumentException.class, () -> EconomyAmounts.fromRawDouble(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> EconomyAmounts.fromRawDouble(Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> EconomyAmounts.fromRawDouble(-0.0d, 0));
        assertThrows(IllegalArgumentException.class, () -> EconomyAmounts.fromRawDouble(
                Math.nextUp(9007199254740992d), 0));
    }
}
