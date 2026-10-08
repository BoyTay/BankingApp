package vn.edu.wallet.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.edu.wallet.api.ApiException;

@Component
public class AuthRateLimiter {
    private static final int LOGIN_ATTEMPTS = 10;
    private static final int REGISTER_ATTEMPTS = 20;
    private final JdbcTemplate db;

    public AuthRateLimiter(JdbcTemplate db) {
        this.db = db;
    }

    public void loginAttempt(String address, String email) {
        take("LOGIN", loginKey(address, email), LOGIN_ATTEMPTS);
    }

    public void loginSucceeded(String address, String email) {
        db.update("DELETE FROM auth_rate_limits WHERE scope='LOGIN' AND key_hash=?",
                hash(loginKey(address, email)));
    }

    public void registrationAttempt(String address) {
        take("REGISTER", address == null ? "unknown" : address, REGISTER_ATTEMPTS);
    }

    private void take(String scope, String key, int limit) {
        Attempt attempt = db.queryForObject("""
                INSERT INTO auth_rate_limits(scope, key_hash, attempts, window_ends_at)
                VALUES (?, ?, 1, now() + interval '60 seconds')
                ON CONFLICT (scope, key_hash) DO UPDATE SET
                    attempts = CASE
                        WHEN auth_rate_limits.window_ends_at <= now() THEN 1
                        ELSE LEAST(auth_rate_limits.attempts + 1, 21)
                    END,
                    window_ends_at = CASE
                        WHEN auth_rate_limits.window_ends_at <= now() THEN now() + interval '60 seconds'
                        ELSE auth_rate_limits.window_ends_at
                    END
                RETURNING attempts,
                    GREATEST(1, CEIL(EXTRACT(EPOCH FROM (window_ends_at - now())))::integer)
                        AS retry_after_seconds
                """, (rs, row) -> new Attempt(rs.getInt("attempts"), rs.getInt("retry_after_seconds")),
                scope, hash(key));
        if (attempt != null && attempt.count() > limit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Quá nhiều yêu cầu. Vui lòng thử lại sau " + attempt.retryAfterSeconds() + " giây.",
                    attempt.retryAfterSeconds());
        }
    }

    private static String loginKey(String address, String email) {
        return (address == null ? "unknown" : address) + '\0'
                + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Attempt(int count, int retryAfterSeconds) {}
}
