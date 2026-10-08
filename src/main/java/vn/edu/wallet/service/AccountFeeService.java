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

@Service
public class AccountFeeService {
    private static final Logger log = LoggerFactory.getLogger(AccountFeeService.class);
    private final JdbcTemplate db;
    private final WalletQueries wallets;
    private final AccountService accounts;
    private final String configuredCheckingMonthlyFee;

    public AccountFeeService(JdbcTemplate db, WalletQueries wallets, AccountService accounts,
            @Value("${account.fees.checking-monthly-dong:5000}") String configuredCheckingMonthlyFee) {
        this.db = db;
        this.wallets = wallets;
        this.accounts = accounts;
        this.configuredCheckingMonthlyFee = configuredCheckingMonthlyFee;
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
        long amount;
        try {
            amount = Long.parseLong(configuredCheckingMonthlyFee.trim());
        } catch (RuntimeException ex) {
            log.error("Invalid checking monthly fee configuration; fee run skipped");
            return new RunResult(0, 0, false);
        }
        if (amount < 1 || amount > RequestChecks.MAX_AMOUNT_DONG) {
            log.error("Checking monthly fee outside allowed range; fee run skipped");
            return new RunResult(0, 0, false);
        }
        Boolean locked = db.queryForObject("""
                SELECT pg_try_advisory_xact_lock(hashtextextended('account-fee-maintenance',0))
                """, Boolean.class);
        if (!Boolean.TRUE.equals(locked)) return new RunResult(0, 0, false);
        int assessed = db.update("""
                INSERT INTO account_fees(id,wallet_id,fee_code,period_start,amount_dong)
                SELECT gen_random_uuid(),w.id,'CHECKING_MONTHLY',period.period_start::date,?
                FROM wallets w CROSS JOIN LATERAL
                  generate_series(w.fee_starts_on,?::date,'1 month'::interval) AS period(period_start)
                WHERE w.account_type='CHECKING' AND w.account_status='ACTIVE'
                ON CONFLICT (wallet_id,fee_code,period_start) DO NOTHING
                """, amount, java.sql.Date.valueOf(asOf.withDayOfMonth(1)));
        List<DueFee> due = db.query("""
                SELECT id,wallet_id,amount_dong FROM account_fees WHERE status='DUE'
                ORDER BY wallet_id,period_start,id LIMIT 1000 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new DueFee(rs.getObject("id", UUID.class),
                rs.getObject("wallet_id", UUID.class), rs.getLong("amount_dong")));
        int paid = 0;
        for (DueFee fee : due) {
            long balance = wallets.lockBalance(fee.walletId());
            if (balance < fee.amountDong()) continue;
            long after = balance - fee.amountDong();
            db.update("UPDATE wallets SET balance_dong=? WHERE id=?", after, fee.walletId());
            db.update("""
                    INSERT INTO ledger_entries(id,wallet_id,fee_id,delta_dong,balance_after_dong)
                    VALUES (?,?,?,?,?)
                    """, UUID.randomUUID(), fee.walletId(), fee.id(), -fee.amountDong(), after);
            db.update("UPDATE account_fees SET status='PAID',paid_at=now() WHERE id=?", fee.id());
            paid++;
        }
        return new RunResult(assessed, paid, true);
    }

    private record DueFee(UUID id, UUID walletId, long amountDong) {}
    public record RunResult(int assessed, int paid, boolean ran) {}
}
