package vn.edu.wallet.core;

import java.util.Objects;
import java.util.UUID;

public record TransferRequest(UUID requestKey, UUID senderWalletId, UUID recipientWalletId, Money amount) {
    public TransferRequest {
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(senderWalletId, "senderWalletId");
        Objects.requireNonNull(recipientWalletId, "recipientWalletId");
        Objects.requireNonNull(amount, "amount");
    }
}
