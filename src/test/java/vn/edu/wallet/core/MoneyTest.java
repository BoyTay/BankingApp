package vn.edu.wallet.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test void negativeAmountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Money(-1));
    }

    @Test void zeroIsAllowed() {
        assertEquals(0, new Money(0).dong());
    }

    @Test void plusAddsExactly() {
        assertEquals(125_011, new Money(125_001).plus(new Money(10)).dong());
    }

    @Test void plusOverflowThrows() {
        assertThrows(ArithmeticException.class, () -> new Money(Long.MAX_VALUE).plus(new Money(1)));
    }

    @Test void minusToZeroIsAllowed() {
        assertEquals(0, new Money(100).minus(new Money(100)).dong());
    }

    @Test void minusBelowZeroIsInsufficientFunds() {
        var error = assertThrows(TransferException.class, () -> new Money(100).minus(new Money(101)));
        assertEquals(TransferErrorCode.INSUFFICIENT_FUNDS, error.code());
    }
}
