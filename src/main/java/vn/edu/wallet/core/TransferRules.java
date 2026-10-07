package vn.edu.wallet.core;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Pure business decision. The server must call it inside a database transaction with wallet row locks. */
public final class TransferRules {
    private TransferRules() {}

    public static TransferReceipt decide(TransferRequest request, Money senderBalance,
                                         Money recipientBalance, TransferReceipt prior,
                                         UUID newTransferId, Instant now) {
        Objects.requireNonNull(request, "request");
        if (prior != null) {
            if (prior.request().equals(request)) {
                return prior;
            }
            throw new TransferException(TransferErrorCode.IDEMPOTENCY_CONFLICT,
                    "Mã yêu cầu đã được dùng cho giao dịch khác");
        }
        if (request.amount().dong() == 0) {
            throw new TransferException(TransferErrorCode.INVALID_AMOUNT, "Số tiền phải lớn hơn 0");
        }
        if (request.senderWalletId().equals(request.recipientWalletId())) {
            throw new TransferException(TransferErrorCode.SELF_TRANSFER,
                    "Không thể chuyển tiền cho chính mình");
        }
        Objects.requireNonNull(senderBalance, "senderBalance");
        Objects.requireNonNull(recipientBalance, "recipientBalance");
        return new TransferReceipt(Objects.requireNonNull(newTransferId, "newTransferId"), request,
                senderBalance.minus(request.amount()), recipientBalance.plus(request.amount()),
                Objects.requireNonNull(now, "now"));
    }
}
