package vn.edu.wallet.notify;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Stores the notification so the web app can show it. */
@Component
public class InAppObserver implements NotificationObserver {
    private final JdbcTemplate db;

    public InAppObserver(JdbcTemplate db) { this.db = db; }

    @Override
    public void update(WalletEvent event) {
        db.update("INSERT INTO notifications(id,user_id,wallet_id,type,title,body) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), event.userId(), event.walletId(), event.type(), event.title(), event.body());
    }
}
