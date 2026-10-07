package vn.edu.wallet.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record Statement(String walletCode, LocalDate from, LocalDate to,
                        List<Line> lines, long totalIncomingDong, long totalOutgoingDong) {
    public record Line(Instant occurredAt, UUID transferId, String direction,
                       String counterpartyWalletCode, long amountDong, long myBalanceAfterDong) {}
}
