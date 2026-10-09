package vn.edu.wallet.notify;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Watches a checking account's balance against the user's threshold. It alerts once when the balance falls
 * below the threshold and re-arms only after the balance is back at or above it, so it never spams.
 */
@Component
public class LowBalanceMonitor {
    private static final Logger log = LoggerFactory.getLogger(LowBalanceMonitor.class);
    private final JdbcTemplate db;
    private final EventSubject events;
    private final TransactionTemplate tx;

    public LowBalanceMonitor(JdbcTemplate db, EventSubject events, PlatformTransactionManager txManager) {
        this.db = db;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Call after changing a wallet balance. Evaluates against the committed balance. */
    public void check(UUID walletId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { evaluate(walletId); }
            });
        } else {
            evaluate(walletId);
        }
    }

    /** Evaluates immediately; also used right after the user changes the threshold. */
    public void evaluate(UUID walletId) {
        try {
            WalletEvent.LowBalance alert = tx.execute(status -> decide(walletId));
            if (alert != null) events.deliver(alert);
        } catch (RuntimeException ex) {
            log.warn("Low balance check failed for wallet {}", walletId, ex);
        }
    }

    private WalletEvent.LowBalance decide(UUID walletId) {
        List<Row> rows = db.query("""
                SELECT w.owner_id,w.wallet_code,w.balance_dong,s.low_balance_dong,s.low_balance_alerted
                FROM wallets w JOIN notification_settings s ON s.wallet_id=w.id
                WHERE w.id=? AND w.account_type='CHECKING' FOR UPDATE OF s
                """, (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3),
                rs.getLong(4), rs.getBoolean(5)), walletId);
        if (rows.isEmpty()) return null;
        Row row = rows.getFirst();
        boolean low = row.threshold() > 0 && row.balance() < row.threshold();
        if (low && !row.alerted()) {
            db.update("UPDATE notification_settings SET low_balance_alerted=true WHERE wallet_id=?", walletId);
            return new WalletEvent.LowBalance(row.ownerId(), walletId, row.code(), row.balance(), row.threshold());
        }
        if (!low && row.alerted()) {
            db.update("UPDATE notification_settings SET low_balance_alerted=false WHERE wallet_id=?", walletId);
        }
        return null;
    }

    private record Row(UUID ownerId, String code, long balance, long threshold, boolean alerted) {}
}
