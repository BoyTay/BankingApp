package vn.edu.wallet.api;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApiDtos {
    private ApiDtos() {}

    public record RegisterRequest(String email, String displayName, String password) {}
    public record LoginRequest(String email, String password) {}
    public record WalletView(UUID walletId, String walletCode, long balanceDong) {}
    public record UserView(UUID userId, String email, String displayName, String role) {}
    public record RegisterView(UUID userId, String email, String displayName, String role, WalletView wallet) {}
    public record LoginView(String accessToken, String tokenType, Instant expiresAt, UserView user) {}
    public record WalletLookup(UUID walletId, String walletCode, String displayName) {}
    public record TransferCreate(UUID requestKey, String recipientWalletCode, JsonNode amountDong) {}
    public record TransferView(UUID transferId, UUID requestKey, String senderWalletCode,
                               String recipientWalletCode, long amountDong, String direction,
                               long myBalanceAfterDong, Instant createdAt) {}
    public record TransferPage(List<TransferView> items, int page, int size, long totalItems) {}
    public record GrantCreate(UUID requestKey, String recipientWalletCode, JsonNode amountDong, String reason) {}
    public record GrantView(UUID grantId, UUID requestKey, UUID adminUserId, String recipientWalletCode,
                            long amountDong, long balanceAfterDong, String reason, Instant createdAt) {}
    public record BalanceMismatchView(UUID walletId, String walletCode, String actualBalanceDong,
                                      String ledgerBalanceDong, String differenceDong) {}
    public record ReconciliationView(Instant checkedAt, long checkedWallets, long mismatchCount,
                                     int page, int size, List<BalanceMismatchView> items) {}
    public record ImportView(UUID batchId, String format, String sourceName, int rowCount, Instant importedAt) {}
    public record ExpenseSummaryView(String key, long amountDong, long count) {}
    public record ExpenseStatsView(String groupBy, String from, String to,
                                   long totalAmountDong, long totalCount,
                                   List<ExpenseSummaryView> items) {}
    public record ErrorView(String code, String message, UUID traceId) {}
}
