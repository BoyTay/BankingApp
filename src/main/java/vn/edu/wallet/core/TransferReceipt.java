package vn.edu.wallet.core;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TransferReceipt(UUID transferId, TransferRequest request,
                              Money senderBalanceAfter, Money recipientBalanceAfter,
                              Instant createdAt) {
    public TransferReceipt {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(senderBalanceAfter, "senderBalanceAfter");
        Objects.requireNonNull(recipientBalanceAfter, "recipientBalanceAfter");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
