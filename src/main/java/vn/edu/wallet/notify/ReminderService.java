package vn.edu.wallet.notify;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes payment reminders. Each reminder is claimed in {@code reminder_log} first, so rerunning the job
 * (or running it on two instances) never sends the same reminder twice for the same period.
 */
@Service
public class ReminderService {
    static final int SAVINGS_NOTICE_DAYS = 3;
    private final JdbcTemplate db;
    private final EventSubject events;

    public ReminderService(JdbcTemplate db, EventSubject events) {
        this.db = db;
        this.events = events;
    }

    /** Returns how many reminders were published; 0 also when another instance holds the lock. */
    @Transactional
    public int run(LocalDate today) {
        Boolean locked = db.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtextextended('payment-reminders',0))", Boolean.class);
        if (!Boolean.TRUE.equals(locked)) return 0;
        String week = today.get(IsoFields.WEEK_BASED_YEAR) + "W" + today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        String month = today.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        int sent = 0;
        // Unpaid fees: remind weekly while the fee stays DUE.
        sent += remind("""
                SELECT f.id,w.id,w.owner_id,w.wallet_code,f.amount_dong,f.period_start
                FROM account_fees f JOIN wallets w ON w.id=f.wallet_id
                WHERE f.status='DUE' AND w.account_status='ACTIVE' ORDER BY f.id
                """, (rs) -> new Candidate("FEE:" + rs.id1() + ":" + week, rs.owner(),
                new WalletEvent.FeeDue(rs.owner(), rs.wallet(), rs.code(), rs.amount(), rs.text())));
        // Credit debt: remind monthly while the account still owes money.
        sent += remind("""
                SELECT w.id,w.id,w.owner_id,w.wallet_code,-w.balance_dong,''
                FROM wallets w WHERE w.account_type='CREDIT' AND w.account_status='ACTIVE' AND w.balance_dong<0
                ORDER BY w.id
                """, (rs) -> new Candidate("CREDIT:" + rs.wallet() + ":" + month, rs.owner(),
                new WalletEvent.CreditDebt(rs.owner(), rs.wallet(), rs.code(), rs.amount())));
        // Savings maturing within the notice window: remind once per account.
        sent += remind("""
                SELECT w.id,w.id,w.owner_id,w.wallet_code,0,s.matures_on::text
                FROM savings_accounts s JOIN wallets w ON w.id=s.wallet_id
                WHERE s.closed_at IS NULL AND s.matures_on BETWEEN ?::date AND ?::date ORDER BY w.id
                """, (rs) -> new Candidate("SAVINGS:" + rs.wallet(), rs.owner(),
                new WalletEvent.SavingsMaturing(rs.owner(), rs.wallet(), rs.code(), rs.text(),
                        java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(rs.text())))),
                java.sql.Date.valueOf(today), java.sql.Date.valueOf(today.plusDays(SAVINGS_NOTICE_DAYS)));
        return sent;
    }

    private int remind(String sql, Function<Row, Candidate> mapper, Object... args) {
        List<Candidate> candidates = db.query(sql, (rs, i) -> mapper.apply(new Row(rs.getString(1),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), rs.getLong(5),
                rs.getString(6))), args);
        int sent = 0;
        for (Candidate candidate : candidates) {
            int claimed = db.update("""
                    INSERT INTO reminder_log(reminder_key,user_id) VALUES (?,?) ON CONFLICT DO NOTHING
                    """, candidate.key(), candidate.userId());
            if (claimed == 1) {
                events.publish(candidate.event());
                sent++;
            }
        }
        return sent;
    }

    private record Row(String id1, UUID wallet, UUID owner, String code, long amount, String text) {}

    private record Candidate(String key, UUID userId, WalletEvent event) {}
}
