package vn.edu.wallet.core;

public enum TransferErrorCode {
    INVALID_AMOUNT,
    SELF_TRANSFER,
    INSUFFICIENT_FUNDS,
    IDEMPOTENCY_CONFLICT
}
