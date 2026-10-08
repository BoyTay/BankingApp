package vn.edu.wallet.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.wallet.account.AccountOpening;
import vn.edu.wallet.api.ApiDtos;
import vn.edu.wallet.api.ApiException;
import vn.edu.wallet.api.RequestChecks;

@Service
public class SavingsService {
    public static final long MIN_PRINCIPAL_DONG = 100_000;
    private final JdbcTemplate db;
    private final WalletQueries wallets;
    private final InternalAccountTransfers transfers;
    private final String configuredTermDays;
    private final String configuredAnnualRateBps;

    public SavingsService(JdbcTemplate db, WalletQueries wallets, InternalAccountTransfers transfers,
            @Value("${account.savings.term-days:90}") String configuredTermDays,
            @Value("${account.savings.annual-rate-bps:400}") String configuredAnnualRateBps) {
        this.db = db;
        this.wallets = wallets;
        this.transfers = transfers;
        this.configuredTermDays = configuredTermDays;
        this.configuredAnnualRateBps = configuredAnnualRateBps;
    }

    @Transactional
    public void open(UUID userId, AccountOpening opening, ApiDtos.AccountCreate request) {
        if (request.fundingAccountId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Thiếu tài khoản nguồn");
        }
        long principal = RequestChecks.amount(request.amountDong());
        if (principal < MIN_PRINCIPAL_DONG) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Tiền gửi tối thiểu 100000 VND");
        }
        int termDays = productNumber(configuredTermDays, 1, 3650);
        int rateBps = productNumber(configuredAnnualRateBps, 1, 10000);
        WalletQueries.WalletRow funding = wallets.ownedBy(userId, request.fundingAccountId());
        if (!"CHECKING".equals(funding.accountType()) || !"ACTIVE".equals(funding.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_OPERATION_NOT_ALLOWED",
                    "Cần tài khoản Thanh toán đang hoạt động để gửi tiết kiệm");
        }
        db.update("""
                INSERT INTO wallets(id,owner_id,wallet_code,account_type,is_default,creation_request_key,fee_starts_on)
                VALUES (?,?,?,'SAVINGS',false,?,?)
                """, opening.id(), opening.ownerId(), opening.code(), opening.requestKey(),
                java.sql.Date.valueOf(opening.feeStartsOn()));
        UUID transferId = transfers.move(funding.id(), opening.id(), principal,
                operationKey("savings-open", opening.id()));
        LocalDate maturesOn = LocalDate.now(ZoneOffset.UTC).plusDays(termDays);
        db.update("""
                INSERT INTO savings_accounts(wallet_id,funding_wallet_id,opening_transfer_id,
                    principal_dong,term_days,annual_rate_bps,matures_on)
                VALUES (?,?,?,?,?,?,?)
                """, opening.id(), funding.id(), transferId, principal, termDays, rateBps,
                java.sql.Date.valueOf(maturesOn));
    }

    public boolean sameOpening(UUID walletId, ApiDtos.AccountCreate request) {
        if (request.fundingAccountId() == null) return false;
        long amount;
        try {
            amount = RequestChecks.amount(request.amountDong());
        } catch (ApiException ex) {
            return false;
        }
        Integer count = db.queryForObject("""
                SELECT count(*) FROM savings_accounts
                WHERE wallet_id=? AND funding_wallet_id=? AND principal_dong=?
                """, Integer.class, walletId, request.fundingAccountId(), amount);
        return count != null && count == 1;
    }

    public ApiDtos.SavingsView get(UUID userId, UUID accountId) {
        List<ApiDtos.SavingsView> rows = db.query("""
                SELECT s.wallet_id,s.funding_wallet_id,s.principal_dong,s.term_days,
                       s.annual_rate_bps,s.matures_on,w.account_status,w.balance_dong
                FROM savings_accounts s JOIN wallets w ON w.id=s.wallet_id
                WHERE s.wallet_id=? AND w.owner_id=?
                """, (rs, row) -> new ApiDtos.SavingsView(rs.getObject("wallet_id", UUID.class),
                rs.getObject("funding_wallet_id", UUID.class), rs.getLong("principal_dong"),
                rs.getInt("term_days"), rs.getInt("annual_rate_bps"),
                rs.getDate("matures_on").toLocalDate().toString(), rs.getString("account_status"),
                rs.getLong("balance_dong")), accountId, userId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Không tìm thấy tài khoản");
        return rows.getFirst();
    }

    @Transactional
    public WithdrawalResult withdraw(UUID userId, UUID accountId, ApiDtos.SavingsWithdraw request) {
        if (request == null || request.requestKey() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Thiếu mã yêu cầu");
        }
        List<SavingsRow> rows = db.query("""
                SELECT s.wallet_id,s.funding_wallet_id,s.principal_dong,s.term_days,
                       s.annual_rate_bps,s.matures_on,s.closing_request_key,
                       s.closing_transfer_id,s.interest_dong,s.fee_dong,s.closed_at,
                       w.account_status
                FROM savings_accounts s JOIN wallets w ON w.id=s.wallet_id
                WHERE s.wallet_id=? AND w.owner_id=? FOR UPDATE OF s
                """, (rs, row) -> new SavingsRow(rs.getObject("wallet_id", UUID.class),
                rs.getObject("funding_wallet_id", UUID.class), rs.getLong("principal_dong"),
                rs.getInt("term_days"), rs.getInt("annual_rate_bps"),
                rs.getDate("matures_on").toLocalDate(),
                rs.getObject("closing_request_key", UUID.class),
                rs.getObject("closing_transfer_id", UUID.class),
                (Long) rs.getObject("interest_dong"), (Long) rs.getObject("fee_dong"),
                rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(),
                rs.getString("account_status")), accountId, userId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Không tìm thấy tài khoản");
        SavingsRow saving = rows.getFirst();
        if (saving.closedAt() != null) {
            if (request.requestKey().equals(saving.closingRequestKey())) {
                return new WithdrawalResult(view(saving), true);
            }
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_CLOSED", "Tài khoản tiết kiệm đã tất toán");
        }
        if (!"ACTIVE".equals(saving.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_CLOSED", "Tài khoản tiết kiệm không hoạt động");
        }
        WalletQueries.WalletRow funding = wallets.ownedBy(userId, saving.fundingWalletId());
        if (!"CHECKING".equals(funding.accountType()) || !"ACTIVE".equals(funding.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_OPERATION_NOT_ALLOWED",
                    "Tài khoản Thanh toán nhận tiền không hoạt động");
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        boolean matured = !today.isBefore(saving.maturesOn());
        long interest = matured ? interest(saving.principalDong(), saving.annualRateBps(), saving.termDays()) : 0;
        long fee = matured ? 0 : earlyFee(saving.principalDong());
        // Lock both wallets in the same order used by ordinary transfers.
        UUID first = accountId.toString().compareTo(funding.id().toString()) <= 0 ? accountId : funding.id();
        UUID second = first.equals(accountId) ? funding.id() : accountId;
        wallets.lockBalance(first);
        wallets.lockBalance(second);
        long savingsBalance = wallets.lockBalance(accountId);
        if (savingsBalance != saving.principalDong()) {
            throw new ApiException(HttpStatus.CONFLICT, "SAVINGS_BALANCE_MISMATCH", "Số dư tiết kiệm không khớp tiền gốc");
        }
        if (fee > 0) postEarlyFee(accountId, fee, today, savingsBalance);
        if (interest > 0) postInterest(accountId, interest, savingsBalance - fee);
        long payout = Math.addExact(saving.principalDong() - fee, interest);
        UUID transferId = transfers.move(accountId, funding.id(), payout,
                operationKey("savings-withdraw", accountId));
        db.update("UPDATE wallets SET account_status='CLOSED' WHERE id=?", accountId);
        db.update("""
                UPDATE savings_accounts SET closing_request_key=?,closing_transfer_id=?,
                    interest_dong=?,fee_dong=?,closed_at=now() WHERE wallet_id=?
                """, request.requestKey(), transferId, interest, fee, accountId);
        SavingsRow closed = db.queryForObject("""
                SELECT wallet_id,funding_wallet_id,principal_dong,term_days,annual_rate_bps,
                       matures_on,closing_request_key,closing_transfer_id,interest_dong,fee_dong,
                       closed_at,'CLOSED' AS account_status
                FROM savings_accounts WHERE wallet_id=?
                """, (rs, row) -> new SavingsRow(rs.getObject("wallet_id", UUID.class),
                rs.getObject("funding_wallet_id", UUID.class), rs.getLong("principal_dong"),
                rs.getInt("term_days"), rs.getInt("annual_rate_bps"),
                rs.getDate("matures_on").toLocalDate(),
                rs.getObject("closing_request_key", UUID.class),
                rs.getObject("closing_transfer_id", UUID.class),
                rs.getLong("interest_dong"), rs.getLong("fee_dong"),
                rs.getTimestamp("closed_at").toInstant(), rs.getString("account_status")), accountId);
        return new WithdrawalResult(view(closed), false);
    }

    private void postEarlyFee(UUID accountId, long fee, LocalDate today, long balance) {
        UUID feeId = UUID.randomUUID();
        db.update("""
                INSERT INTO account_fees(id,wallet_id,fee_code,period_start,amount_dong,status,paid_at)
                VALUES (?,?, 'SAVINGS_EARLY_WITHDRAWAL',?,?,'PAID',now())
                """, feeId, accountId, java.sql.Date.valueOf(today), fee);
        long after = balance - fee;
        db.update("UPDATE wallets SET balance_dong=? WHERE id=?", after, accountId);
        db.update("""
                INSERT INTO ledger_entries(id,wallet_id,fee_id,delta_dong,balance_after_dong)
                VALUES (?,?,?,?,?)
                """, UUID.randomUUID(), accountId, feeId, -fee, after);
    }

    private void postInterest(UUID accountId, long interest, long balance) {
        UUID interestId = UUID.randomUUID();
        long after;
        try {
            after = Math.addExact(balance, interest);
        } catch (ArithmeticException ex) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BALANCE_OVERFLOW", "Số dư vượt giới hạn");
        }
        db.update("INSERT INTO savings_interest(id,wallet_id,amount_dong) VALUES (?,?,?)",
                interestId, accountId, interest);
        db.update("UPDATE wallets SET balance_dong=? WHERE id=?", after, accountId);
        db.update("""
                INSERT INTO ledger_entries(id,wallet_id,interest_id,delta_dong,balance_after_dong)
                VALUES (?,?,?,?,?)
                """, UUID.randomUUID(), accountId, interestId, interest, after);
    }

    private static ApiDtos.SavingsWithdrawalView view(SavingsRow row) {
        long interest = row.interestDong();
        long fee = row.feeDong();
        long payout = row.principalDong() + interest - fee;
        return new ApiDtos.SavingsWithdrawalView(row.walletId(), row.closingRequestKey(),
                row.principalDong(), interest, fee, payout, fee == 0,
                row.closingTransferId(), row.closedAt());
    }

    private static long interest(long principal, int annualRateBps, int days) {
        return BigDecimal.valueOf(principal).multiply(BigDecimal.valueOf(annualRateBps))
                .multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(3_650_000L), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static long earlyFee(long principal) {
        return BigDecimal.valueOf(principal).multiply(BigDecimal.valueOf(50))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static int productNumber(String value, int min, int max) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= min && parsed <= max) return parsed;
        } catch (RuntimeException ignored) { }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SAVINGS_PRODUCT_UNAVAILABLE",
                "Cấu hình tiết kiệm chưa hợp lệ");
    }

    private static UUID operationKey(String operation, UUID walletId) {
        return UUID.nameUUIDFromBytes((operation + ':' + walletId).getBytes(StandardCharsets.UTF_8));
    }

    private record SavingsRow(UUID walletId, UUID fundingWalletId, long principalDong,
                              int termDays, int annualRateBps, LocalDate maturesOn,
                              UUID closingRequestKey, UUID closingTransferId,
                              Long interestDong, Long feeDong, java.time.Instant closedAt,
                              String status) {}
    public record WithdrawalResult(ApiDtos.SavingsWithdrawalView withdrawal, boolean replayed) {}
}
