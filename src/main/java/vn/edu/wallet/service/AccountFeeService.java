package vn.edu.wallet.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.wallet.api.ApiDtos;
import vn.edu.wallet.api.RequestChecks;
import vn.edu.wallet.notify.LowBalanceMonitor;

@Service
public class AccountFeeService {
    private static final Logger log = LoggerFactory.getLogger(AccountFeeService.class);
    private final JdbcTemplate db;
    private final WalletQueries wallets;
    private final AccountService accounts;
    private final LowBalanceMonitor lowBalance;
    private final String configuredCheckingMonthlyFee;
    private final String configuredCreditAnnualFee;

    public AccountFeeService(JdbcTemplate db, WalletQueries wallets, AccountService accounts,
            LowBalanceMonitor lowBalance,
            @Value("${account.fees.checking-monthly-dong:5000}") String configuredCheckingMonthlyFee,
            @Value("${account.fees.credit-annual-dong:20000}") String configuredCreditAnnualFee) {
        this.db = db;
        this.wallets = wallets;
        this.accounts = accounts;
        this.lowBalance = lowBalance;
        this.configuredCheckingMonthlyFee = configuredCheckingMonthlyFee;
        this.configuredCreditAnnualFee = configuredCreditAnnualFee;
    }

    public List<ApiDtos.AccountFeeView> list(UUID ownerId, UUID accountId) {
        accounts.get(ownerId, accountId);
        return db.query("""
                SELECT id,fee_code,period_start,amount_dong,status,paid_at
                FROM account_fees WHERE wallet_id=? ORDER BY period_start DESC,id DESC
                """, (rs, row) -> new ApiDtos.AccountFeeView(
                rs.getObject("id", UUID.class), rs.getString("fee_code"),
                rs.getDate("period_start").toLocalDate().toString(), rs.getLong("amount_dong"),
                rs.getString("status"), rs.getTimestamp("paid_at") == null
                        ? null : rs.getTimestamp("paid_at").toInstant()), accountId);
    }

    @Transactional
    public RunResult assessAndCollect(LocalDate asOf) {
        Long checkingAmount = validFee(configuredCheckingMonthlyFee, "checking monthly");
        Long creditAmount = validFee(configuredCreditAnnualFee, "credit annual");
        if (checkingAmount == null && creditAmount == null) return new RunResult(0, 0, false);
        Boolean locked = db.queryForObject("""
                SELECT pg_try_advisory_xact_lock(hashtextextended('account-fee-maintenance',0))
                """, Boolean.class);
        if (!Boolean.TRUE.equals(locked)) return new RunResult(0, 0, false);
        int assessed = 0;
        if (checkingAmount != null) assessed += db.update("""
                INSERT INTO account_fees(id,wallet_id,fee_code,period_start,amount_dong)
                SELECT gen_random_uuid(),w.id,'CHECKING_MONTHLY',period.period_start::date,?
                FROM wallets w CROSS JOIN LATERAL
                  generate_series(w.fee_starts_on,?::date,'1 month'::interval) AS period(period_start)
                WHERE w.account_type='CHECKING' AND w.account_status='ACTIVE'
                ON CONFLICT (wallet_id,fee_code,period_start) DO NOTHING
                """, checkingAmount, java.sql.Date.valueOf(asOf.withDayOfMonth(1)));
        if (creditAmount != null) assessed += db.update("""
                INSERT INTO account_fees(id,wallet_id,fee_code,period_start,amount_dong)
                SELECT gen_random_uuid(),w.id,'CREDIT_ANNUAL',period.period_start::date,?
                FROM wallets w CROSS JOIN LATERAL
                  generate_series(w.fee_starts_on,?::date,'1 year'::interval) AS period(period_start)
                WHERE w.account_type='CREDIT' AND w.account_status='ACTIVE'
                ON CONFLICT (wallet_id,fee_code,period_start) DO NOTHING
                """, creditAmount, java.sql.Date.valueOf(asOf));
        List<DueFee> due = db.query("""
                SELECT id,wallet_id,amount_dong FROM account_fees WHERE status='DUE'
                ORDER BY wallet_id,period_start,id LIMIT 1000 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new DueFee(rs.getObject("id", UUID.class),
                rs.getObject("wallet_id", UUID.class), rs.getLong("amount_dong")));
        int paid = 0;
        for (DueFee fee : due) {
            long balance = wallets.lockBalance(fee.walletId());
            FeeWallet account = db.queryForObject("SELECT account_type,credit_limit_dong FROM wallets WHERE id=?",
                    (rs, row) -> new FeeWallet(rs.getString(1), rs.getLong(2)), fee.walletId());
            if ("CHECKING".equals(account.type()) && balance < fee.amountDong()) continue;
            if ("CREDIT".equals(account.type()) && account.limitDong() + balance < fee.amountDong()) continue;
            if (!"CHECKING".equals(account.type()) && !"CREDIT".equals(account.type())) continue;
            long after = balance - fee.amountDong();
            db.update("UPDATE wallets SET balance_dong=? WHERE id=?", after, fee.walletId());
            db.update("""
                    INSERT INTO ledger_entries(id,wallet_id,fee_id,delta_dong,balance_after_dong)
                    VALUES (?,?,?,?,?)
                    """, UUID.randomUUID(), fee.walletId(), fee.id(), -fee.amountDong(), after);
            db.update("UPDATE account_fees SET status='PAID',paid_at=now() WHERE id=?", fee.id());
            lowBalance.check(fee.walletId());
            paid++;
        }
        return new RunResult(assessed, paid, true);
    }

    private Long validFee(String setting, String name) {
        try {
            long amount = Long.parseLong(setting.trim());
            if (amount >= 1 && amount <= RequestChecks.MAX_AMOUNT_DONG) return amount;
        } catch (RuntimeException ignored) { }
        log.error("Invalid {} fee configuration; assessment skipped", name);
        return null;
    }

    private record FeeWallet(String type, long limitDong) {}

    private record DueFee(UUID id, UUID walletId, long amountDong) {}
    public record RunResult(int assessed, int paid, boolean ran) {}
}
