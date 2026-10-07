package vn.edu.wallet.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.wallet.api.ApiDtos;
import vn.edu.wallet.api.ApiException;

@Service
public class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

    public AuthService(JdbcTemplate db) { this.db = db; }

    @Transactional
    public ApiDtos.RegisterView register(ApiDtos.RegisterRequest input) {
        if (input == null || input.email() == null || input.displayName() == null || input.password() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Thiếu thông tin đăng ký");
        }
        String email = input.email().trim().toLowerCase(Locale.ROOT);
        String name = input.displayName().trim();
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || email.length() > 254) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL", "Email không hợp lệ");
        }
        if (name.isEmpty() || name.length() > 120) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Tên hiển thị không hợp lệ");
        }
        if (input.password().length() < 10 || input.password().length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", "Mật khẩu cần từ 10 đến 128 ký tự");
        }
        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        String code = walletCode(walletId);
        try {
            db.update("INSERT INTO app_users(id,email,display_name,password_hash,role) VALUES (?,?,?,?, 'USER')",
                    userId, email, name, passwords.encode(input.password()));
            db.update("INSERT INTO wallets(id,owner_id,wallet_code,balance_dong) VALUES (?,?,?,0)",
                    walletId, userId, code);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "Email đã được sử dụng");
        }
        return new ApiDtos.RegisterView(userId, email, name, "USER", new ApiDtos.WalletView(walletId, code, 0));
    }

    public ApiDtos.LoginView login(ApiDtos.LoginRequest input) {
        if (input == null || input.email() == null || input.password() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Thiếu email hoặc mật khẩu");
        }
        String email = input.email().trim().toLowerCase(Locale.ROOT);
        List<UserRow> users = db.query("SELECT id,email,display_name,password_hash,role FROM app_users WHERE email=?",
                (rs, row) -> new UserRow(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("display_name"), rs.getString("password_hash"), rs.getString("role")), email);
        if (users.isEmpty() || !passwords.matches(input.password(), users.getFirst().hash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email hoặc mật khẩu không đúng");
        }
        UserRow user = users.getFirst();
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant expiresAt = Instant.now().plus(8, ChronoUnit.HOURS);
        db.update("INSERT INTO auth_sessions(id,user_id,token_hash,expires_at) VALUES (?,?,?,?)",
                UUID.randomUUID(), user.id(), tokenHash(token), java.sql.Timestamp.from(expiresAt));
        return new ApiDtos.LoginView(token, "Bearer", expiresAt,
                new ApiDtos.UserView(user.id(), user.email(), user.name(), user.role()));
    }

    public Principal authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Cần đăng nhập");
        }
        String token = authorization.substring(7);
        List<Principal> rows = db.query("""
                SELECT s.id AS session_id, u.id AS user_id, u.role
                FROM auth_sessions s JOIN app_users u ON u.id=s.user_id
                WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at > now()
                """, (rs, row) -> new Principal(rs.getObject("user_id", UUID.class),
                rs.getObject("session_id", UUID.class), rs.getString("role")), tokenHash(token));
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Phiên đăng nhập không hợp lệ");
        }
        return rows.getFirst();
    }

    public void logout(Principal principal) {
        db.update("UPDATE auth_sessions SET revoked_at=now() WHERE id=? AND revoked_at IS NULL", principal.sessionId());
    }

    @Transactional
    public void bootstrapAdmin(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.length() < 10) return;
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        Integer count = db.queryForObject("SELECT count(*) FROM app_users WHERE email=?", Integer.class, normalized);
        if (count != null && count > 0) return;
        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        db.update("INSERT INTO app_users(id,email,display_name,password_hash,role) VALUES (?,?,?,?, 'ADMIN')",
                userId, normalized, "Demo Admin", passwords.encode(password));
        db.update("INSERT INTO wallets(id,owner_id,wallet_code,balance_dong) VALUES (?,?,?,0)",
                walletId, userId, walletCode(walletId));
    }

    private static String walletCode(UUID id) {
        return "WLT" + id.toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
    }

    private static String tokenHash(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record UserRow(UUID id, String email, String name, String hash, String role) {}
}
