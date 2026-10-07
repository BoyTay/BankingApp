package vn.edu.wallet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferRulesTest {
    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Test void transferMovesExactWholeDong() {
        var request = new TransferRequest(key, sender, recipient, new Money(125_001));
        var receipt = TransferRules.decide(request, new Money(200_000), new Money(10),
                null, UUID.randomUUID(), now);
        assertEquals(74_999, receipt.senderBalanceAfter().dong());
        assertEquals(125_011, receipt.recipientBalanceAfter().dong());
    }

    @Test void insufficientFundsFails() {
        var request = new TransferRequest(key, sender, recipient, new Money(101));
        var error = assertThrows(TransferException.class, () -> TransferRules.decide(
                request, new Money(100), new Money(0), null, UUID.randomUUID(), now));
        assertEquals(TransferErrorCode.INSUFFICIENT_FUNDS, error.code());
    }

    @Test void zeroAndSelfTransferFail() {
        var zero = new TransferRequest(key, sender, recipient, new Money(0));
        assertEquals(TransferErrorCode.INVALID_AMOUNT, assertThrows(TransferException.class,
                () -> TransferRules.decide(zero, new Money(100), new Money(0), null,
                        UUID.randomUUID(), now)).code());
        var self = new TransferRequest(key, sender, sender, new Money(1));
        assertEquals(TransferErrorCode.SELF_TRANSFER, assertThrows(TransferException.class,
                () -> TransferRules.decide(self, new Money(100), new Money(0), null,
                        UUID.randomUUID(), now)).code());
    }

    @Test void retryReturnsOriginalReceiptWithoutReapplyingTransfer() {
        var request = new TransferRequest(key, sender, recipient, new Money(20));
        var original = TransferRules.decide(request, new Money(100), new Money(0), null,
                UUID.randomUUID(), now);
        var retried = TransferRules.decide(request, new Money(80), new Money(20), original,
                UUID.randomUUID(), now.plusSeconds(10));
        assertSame(original, retried);
    }

    @Test void sameKeyWithDifferentPayloadFails() {
        var originalRequest = new TransferRequest(key, sender, recipient, new Money(20));
        var original = TransferRules.decide(originalRequest, new Money(100), new Money(0),
                null, UUID.randomUUID(), now);
        var changed = new TransferRequest(key, sender, recipient, new Money(21));
        assertEquals(TransferErrorCode.IDEMPOTENCY_CONFLICT, assertThrows(TransferException.class,
                () -> TransferRules.decide(changed, new Money(80), new Money(20), original,
                        UUID.randomUUID(), now)).code());
    }

    @Test void moneyRejectsNegativeAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new Money(-1));
        assertThrows(ArithmeticException.class, () -> new Money(Long.MAX_VALUE).plus(new Money(1)));
    }
}
