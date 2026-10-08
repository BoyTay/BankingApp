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
    public record AccountCreate(UUID requestKey, String type, UUID fundingAccountId,
                                JsonNode amountDong) {}
    public record AccountView(UUID accountId, String accountCode, String accountType,
                              String status, boolean isDefault, long balanceDong) {}
    public record AccountFeeView(UUID feeId, String feeCode, String periodStart,
                                 long amountDong, String status, Instant paidAt) {}
    public record SavingsView(UUID accountId, UUID fundingAccountId, long principalDong,
                              int termDays, int annualRateBps, String maturesOn,
                              String status, long balanceDong) {}
    public record SavingsWithdraw(UUID requestKey) {}
    public record SavingsWithdrawalView(UUID accountId, UUID requestKey, long principalDong,
                                        long interestDong, long feeDong, long payoutDong,
                                        boolean matured, UUID transferId, Instant closedAt) {}
    public record CreditView(UUID accountId, String accountCode, String status,
                             long limitDong, long debtDong, long availableDong) {}
    public record CreditSpend(UUID requestKey, JsonNode amountDong, String description) {}
    public record CreditSpendView(UUID chargeId, UUID requestKey, long amountDong,
                                  String description, long debtAfterDong, Instant createdAt) {}
    public record CreditRepay(UUID requestKey, UUID sourceAccountId, JsonNode amountDong) {}
    public record CreditRepayView(UUID repaymentId, UUID requestKey, UUID sourceAccountId,
                                  long amountDong, long debtAfterDong, Instant createdAt) {}
    public record CreditLimitSet(UUID requestKey, JsonNode limitDong) {}
    public record CreditLimitView(UUID changeId, UUID accountId, long limitDong,
                                  long debtDong, Instant createdAt) {}
    public record CreditActivityView(Instant createdAt, String type, long amountDong,
                                     long debtAfterDong, String description) {}
    public record AccountClose(UUID requestKey) {}
    public record AccountCloseView(UUID accountId, String status, UUID requestKey, Instant closedAt) {}
    public record UserView(UUID userId, String email, String displayName, String role) {}
    public record RegisterView(UUID userId, String email, String displayName, String role, WalletView wallet) {}
    public record LoginView(String accessToken, String tokenType, Instant expiresAt, UserView user) {}
    public record WalletLookup(UUID walletId, String walletCode, String displayName) {}
    public record TransferCreate(UUID requestKey, String recipientWalletCode, JsonNode amountDong,
                                 UUID sourceAccountId) {}
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
