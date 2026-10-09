package vn.edu.wallet.service;

import vn.edu.wallet.notify.LowBalanceMonitor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.wallet.api.ApiDtos;
import vn.edu.wallet.api.ApiException;
import vn.edu.wallet.api.RequestChecks;

@Service
public class CreditService {
    private final JdbcTemplate db;
    private final WalletQueries wallets;
    private final LowBalanceMonitor lowBalance;

    public CreditService(JdbcTemplate db, WalletQueries wallets, LowBalanceMonitor lowBalance) {
        this.lowBalance = lowBalance;
        this.db = db;
        this.wallets = wallets;
    }

    public ApiDtos.CreditView get(UUID userId, UUID accountId) {
        List<ApiDtos.CreditView> rows = db.query("""
                SELECT id,wallet_code,account_status,credit_limit_dong,balance_dong
                FROM wallets WHERE id=? AND owner_id=? AND account_type='CREDIT'
                """, (rs, row) -> new ApiDtos.CreditView(rs.getObject("id", UUID.class),
                rs.getString("wallet_code"), rs.getString("account_status"),
                rs.getLong("credit_limit_dong"), -rs.getLong("balance_dong"),
                rs.getLong("credit_limit_dong") + rs.getLong("balance_dong")), accountId, userId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Không tìm thấy tài khoản");
        return rows.getFirst();
    }

    @Transactional
    public SpendResult spend(UUID userId, UUID accountId, ApiDtos.CreditSpend request) {
        if (request == null || request.requestKey() == null || request.description() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Thiếu thông tin sử dụng hạn mức");
        }
        long amount = RequestChecks.amount(request.amountDong());
        String description = request.description().trim();
        if (description.isEmpty() || description.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Mô tả phải từ 1 đến 200 ký tự");
        }
        get(userId, accountId);
        advisory(accountId + ":credit-spend:" + request.requestKey());
        List<ApiDtos.CreditSpendView> prior = db.query("""
                SELECT id,request_key,amount_dong,description,balance_after_dong,created_at
                FROM credit_charges WHERE wallet_id=? AND request_key=?
                """, (rs, row) -> new ApiDtos.CreditSpendView(rs.getObject("id", UUID.class),
                rs.getObject("request_key", UUID.class), rs.getLong("amount_dong"),
                rs.getString("description"), -rs.getLong("balance_after_dong"),
                rs.getTimestamp("created_at").toInstant()), accountId, request.requestKey());
        if (!prior.isEmpty()) {
            ApiDtos.CreditSpendView old = prior.getFirst();
            if (old.amountDong() == amount && old.description().equals(description)) return new SpendResult(old, true);
            throw new ApiException(HttpStatus.CONFLICT, "CREDIT_KEY_CONFLICT", "Mã yêu cầu đã dùng cho khoản khác");
        }
        requireActiveCredit(userId, accountId);
        CreditBalance current = lockCredit(accountId);
        if (current.limitDong() == 0 || amount > current.limitDong() + current.balanceDong()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CREDIT_LIMIT_EXCEEDED",
                    "Số tiền vượt hạn mức còn lại");
        }
        long after = current.balanceDong() - amount;
        UUID id = UUID.randomUUID();
        db.update("UPDATE wallets SET balance_dong=? WHERE id=?", after, accountId);
        db.update("""
                INSERT INTO credit_charges(id,wallet_id,request_key,amount_dong,description,balance_after_dong)
                VALUES (?,?,?,?,?,?)
                """, id, accountId, request.requestKey(), amount, description, after);
        db.update("""
                INSERT INTO ledger_entries(id,wallet_id,credit_charge_id,delta_dong,balance_after_dong)
                VALUES (?,?,?,?,?)
                """, UUID.randomUUID(), accountId, id, -amount, after);
        return new SpendResult(spendById(id), false);
    }

    @Transactional
    public RepayResult repay(UUID userId, UUID accountId, ApiDtos.CreditRepay request) {
        if (request == null || request.requestKey() == null || request.sourceAccountId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Thiếu thông tin hoàn trả");
        }
        long amount = RequestChecks.amount(request.amountDong());
        get(userId, accountId);
        WalletQueries.WalletRow source = wallets.ownedBy(userId, request.sourceAccountId());
        advisory(accountId + ":credit-repay:" + request.requestKey());
        List<ApiDtos.CreditRepayView> prior = db.query("""
                SELECT id,request_key,source_wallet_id,amount_dong,credit_balance_after_dong,created_at
                FROM credit_repayments WHERE credit_wallet_id=? AND request_key=?
                """, (rs, row) -> new ApiDtos.CreditRepayView(rs.getObject("id", UUID.class),
                rs.getObject("request_key", UUID.class), rs.getObject("source_wallet_id", UUID.class),
                rs.getLong("amount_dong"), -rs.getLong("credit_balance_after_dong"),
                rs.getTimestamp("created_at").toInstant()), accountId, request.requestKey());
        if (!prior.isEmpty()) {
            ApiDtos.CreditRepayView old = prior.getFirst();
            if (old.amountDong() == amount && old.sourceAccountId().equals(source.id())) {
                return new RepayResult(old, true);
            }
            throw new ApiException(HttpStatus.CONFLICT, "CREDIT_KEY_CONFLICT", "Mã yêu cầu đã dùng cho khoản khác");
        }
        requireActiveCredit(userId, accountId);
        if (!"CHECKING".equals(source.accountType()) || !"ACTIVE".equals(source.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_OPERATION_NOT_ALLOWED",
                    "Cần tài khoản Thanh toán đang hoạt động để hoàn trả");
        }
        UUID first = accountId.toString().compareTo(source.id().toString()) <= 0 ? accountId : source.id();
        UUID second = first.equals(accountId) ? source.id() : accountId;
        long firstBalance = wallets.lockBalance(first);
        long secondBalance = wallets.lockBalance(second);
        long creditBalance = first.equals(accountId) ? firstBalance : secondBalance;
        long sourceBalance = first.equals(source.id()) ? firstBalance : secondBalance;
        if (creditBalance >= 0 || amount > -creditBalance) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CREDIT_OVERPAYMENT", "Số tiền vượt dư nợ");
        }
        if (sourceBalance < amount) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Số dư không đủ");
        }
        long sourceAfter = sourceBalance - amount;
        long creditAfter = creditBalance + amount;
        UUID id = UUID.randomUUID();
        db.update("UPDATE wallets SET balance_dong=? WHERE id=?", sourceAfter, source.id());
        db.update("UPDATE wallets SET balance_dong=? WHERE id=?", creditAfter, accountId);
        lowBalance.check(source.id());
        db.update("""
                INSERT INTO credit_repayments(id,credit_wallet_id,source_wallet_id,request_key,amount_dong,
                    source_balance_after_dong,credit_balance_after_dong)
                VALUES (?,?,?,?,?,?,?)
                """, id, accountId, source.id(), request.requestKey(), amount, sourceAfter, creditAfter);
        db.update("""
                INSERT INTO ledger_entries(id,wallet_id,credit_repayment_id,delta_dong,balance_after_dong)
                VALUES (?,?,?,?,?)
                """, UUID.randomUUID(), source.id(), id, -amount, sourceAfter);
        db.update("""
                INSERT INTO ledger_entries(id,wallet_id,credit_repayment_id,delta_dong,balance_after_dong)
                VALUES (?,?,?,?,?)
                """, UUID.randomUUID(), accountId, id, amount, creditAfter);
        return new RepayResult(repayById(id), false);
    }

    @Transactional
    public LimitResult setLimit(UUID adminId, UUID accountId, ApiDtos.CreditLimitSet request) {
        if (request == null || request.requestKey() == null || request.limitDong() == null
                || !request.limitDong().isIntegralNumber() || !request.limitDong().canConvertToLong()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Hạn mức phải là số nguyên VND");
        }
        long limit = request.limitDong().longValue();
        if (limit < 0 || limit > RequestChecks.MAX_AMOUNT_DONG) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Hạn mức không hợp lệ");
        }
        advisory(adminId + ":credit-limit:" + request.requestKey());
        List<ApiDtos.CreditLimitView> prior = db.query("""
                SELECT c.id,c.wallet_id,c.limit_dong,c.created_at,c.debt_dong
                FROM credit_limit_changes c
                WHERE c.admin_user_id=? AND c.request_key=?
                """, (rs, row) -> new ApiDtos.CreditLimitView(rs.getObject("id", UUID.class),
                rs.getObject("wallet_id", UUID.class), rs.getLong("limit_dong"),
                rs.getLong("debt_dong"), rs.getTimestamp("created_at").toInstant()),
                adminId, request.requestKey());
        if (!prior.isEmpty()) {
            ApiDtos.CreditLimitView old = prior.getFirst();
            if (old.accountId().equals(accountId) && old.limitDong() == limit) return new LimitResult(old, true);
            throw new ApiException(HttpStatus.CONFLICT, "CREDIT_KEY_CONFLICT", "Mã yêu cầu đã dùng cho hạn mức khác");
        }
        List<CreditBalance> rows = db.query("""
                SELECT balance_dong,credit_limit_dong FROM wallets
                WHERE id=? AND account_type='CREDIT' AND account_status='ACTIVE' FOR UPDATE
                """, (rs, row) -> new CreditBalance(rs.getLong("balance_dong"),
                rs.getLong("credit_limit_dong")), accountId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Không tìm thấy Tín dụng");
        long debt = -rows.getFirst().balanceDong();
        if (limit < debt) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LIMIT_BELOW_DEBT",
                    "Hạn mức mới thấp hơn dư nợ hiện tại");
        }
        UUID id = UUID.randomUUID();
        db.update("UPDATE wallets SET credit_limit_dong=? WHERE id=?", limit, accountId);
        db.update("""
                INSERT INTO credit_limit_changes(id,wallet_id,admin_user_id,request_key,limit_dong,debt_dong)
                VALUES (?,?,?,?,?,?)
                """, id, accountId, adminId, request.requestKey(), limit, debt);
        Instant at = db.queryForObject("SELECT created_at FROM credit_limit_changes WHERE id=?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), id);
        return new LimitResult(new ApiDtos.CreditLimitView(id, accountId, limit, debt, at), false);
    }

    public List<ApiDtos.CreditActivityView> activity(UUID userId, UUID accountId) {
        get(userId, accountId);
        return db.query("""
                SELECT l.created_at,l.delta_dong,l.balance_after_dong,
                       CASE WHEN l.credit_charge_id IS NOT NULL THEN 'CHARGE'
                            WHEN l.credit_repayment_id IS NOT NULL THEN 'REPAYMENT'
                            ELSE 'FEE' END AS kind,
                       coalesce(c.description,f.fee_code,'Hoàn trả') AS description
                FROM ledger_entries l
                LEFT JOIN credit_charges c ON c.id=l.credit_charge_id
                LEFT JOIN account_fees f ON f.id=l.fee_id
                WHERE l.wallet_id=? AND
                    (l.credit_charge_id IS NOT NULL OR l.credit_repayment_id IS NOT NULL
                     OR l.fee_id IS NOT NULL)
                ORDER BY l.created_at DESC,l.id DESC LIMIT 100
                """, (rs, row) -> new ApiDtos.CreditActivityView(rs.getTimestamp("created_at").toInstant(),
                rs.getString("kind"), Math.abs(rs.getLong("delta_dong")),
                -rs.getLong("balance_after_dong"), rs.getString("description")), accountId);
    }

    private WalletQueries.WalletRow requireActiveCredit(UUID userId, UUID accountId) {
        WalletQueries.WalletRow account = wallets.ownedBy(userId, accountId);
        if (!"CREDIT".equals(account.accountType()) || !"ACTIVE".equals(account.status())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Không tìm thấy Tín dụng đang hoạt động");
        }
        return account;
    }

    private CreditBalance lockCredit(UUID accountId) {
        return db.queryForObject("SELECT balance_dong,credit_limit_dong FROM wallets WHERE id=? FOR UPDATE",
                (rs, row) -> new CreditBalance(rs.getLong(1), rs.getLong(2)), accountId);
    }

    private void advisory(String key) {
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text),0))", key);
    }

    private ApiDtos.CreditSpendView spendById(UUID id) {
        return db.queryForObject("""
                SELECT id,request_key,amount_dong,description,balance_after_dong,created_at
                FROM credit_charges WHERE id=?
                """, (rs, row) -> new ApiDtos.CreditSpendView(rs.getObject("id", UUID.class),
                rs.getObject("request_key", UUID.class), rs.getLong("amount_dong"),
                rs.getString("description"), -rs.getLong("balance_after_dong"),
                rs.getTimestamp("created_at").toInstant()), id);
    }

    private ApiDtos.CreditRepayView repayById(UUID id) {
        return db.queryForObject("""
                SELECT id,request_key,source_wallet_id,amount_dong,credit_balance_after_dong,created_at
                FROM credit_repayments WHERE id=?
                """, (rs, row) -> new ApiDtos.CreditRepayView(rs.getObject("id", UUID.class),
                rs.getObject("request_key", UUID.class), rs.getObject("source_wallet_id", UUID.class),
                rs.getLong("amount_dong"), -rs.getLong("credit_balance_after_dong"),
                rs.getTimestamp("created_at").toInstant()), id);
    }

    private record CreditBalance(long balanceDong, long limitDong) {}
    public record SpendResult(ApiDtos.CreditSpendView view, boolean replayed) {}
    public record RepayResult(ApiDtos.CreditRepayView view, boolean replayed) {}
    public record LimitResult(ApiDtos.CreditLimitView view, boolean replayed) {}
}
