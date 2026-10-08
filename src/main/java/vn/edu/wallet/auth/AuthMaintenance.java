package vn.edu.wallet.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AuthMaintenance {
    private final JdbcTemplate db;

    public AuthMaintenance(JdbcTemplate db) {
        this.db = db;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    @Transactional
    public void purgeExpired() {
        db.update("DELETE FROM auth_sessions WHERE expires_at <= now() OR revoked_at IS NOT NULL");
        db.update("DELETE FROM auth_rate_limits WHERE window_ends_at <= now()");
    }
}
