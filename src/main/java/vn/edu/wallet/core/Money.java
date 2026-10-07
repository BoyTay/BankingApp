package vn.edu.wallet.core;

/** Whole Vietnamese dong. Wallet balances may be zero; transfer amounts must be positive. */
public record Money(long dong) {
    public Money {
        if (dong < 0) {
            throw new IllegalArgumentException("Số tiền không được âm");
        }
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(dong, other.dong));
    }

    public Money minus(Money other) {
        if (dong < other.dong) {
            throw new TransferException(TransferErrorCode.INSUFFICIENT_FUNDS, "Số dư không đủ");
        }
        return new Money(dong - other.dong);
    }
}
