package vn.edu.wallet.notify;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Stores the notification so the web app can show it. Runs after the caller committed, in its own transaction. */
@Component
public class InAppObserver implements NotificationObserver {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public InAppObserver(JdbcTemplate db, PlatformTransactionManager txManager) {
        this.db = db;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void update(WalletEvent event) {
        tx.executeWithoutResult(status -> db.update(
                "INSERT INTO notifications(id,user_id,wallet_id,type,title,body) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), event.userId(), event.walletId(), event.type(), event.title(), event.body()));
    }
}
