package vn.edu.wallet.notify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import vn.edu.wallet.api.ApiException;
import vn.edu.wallet.api.RequestChecks;
import vn.edu.wallet.service.WalletQueries;

@Service
public class NotificationService {
    private final JdbcTemplate db;
    private final WalletQueries wallets;
    private final LowBalanceMonitor lowBalance;

    public NotificationService(JdbcTemplate db, WalletQueries wallets, LowBalanceMonitor lowBalance) {
        this.db = db;
        this.wallets = wallets;
        this.lowBalance = lowBalance;
    }

    public NotificationPage list(UUID userId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Phân trang không hợp lệ");
        }
        List<NotificationView> items = db.query("""
                SELECT id,wallet_id,type,title,body,created_at,read_at FROM notifications
                WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                """, (rs, i) -> new NotificationView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant(),
                rs.getTimestamp(7) != null), userId, size, (long) page * size);
        Long total = db.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?", Long.class, userId);
        return new NotificationPage(items, page, size, total == null ? 0 : total, unread(userId));
    }

    public long unread(UUID userId) {
        Long n = db.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND read_at IS NULL",
                Long.class, userId);
        return n == null ? 0 : n;
    }

    public void markRead(UUID userId, UUID id) {
        int changed = db.update("UPDATE notifications SET read_at=coalesce(read_at,now()) WHERE id=? AND user_id=?",
                id, userId);
        if (changed == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Không tìm thấy thông báo");
        }
    }

    public void markAllRead(UUID userId) {
        db.update("UPDATE notifications SET read_at=now() WHERE user_id=? AND read_at IS NULL", userId);
    }

    public SettingsView settings(UUID userId, UUID accountId) {
        WalletQueries.WalletRow wallet = wallets.ownedBy(userId, accountId);
        List<Long> rows = db.queryForList("SELECT low_balance_dong FROM notification_settings WHERE wallet_id=?",
                Long.class, wallet.id());
        return new SettingsView(wallet.id(), rows.isEmpty() ? 0 : rows.getFirst());
    }

    public SettingsView updateSettings(UUID userId, UUID accountId, JsonNode lowBalanceDong) {
        WalletQueries.WalletRow wallet = wallets.ownedBy(userId, accountId);
        if (!"CHECKING".equals(wallet.accountType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOTIFICATION_NOT_SUPPORTED",
                    "Chỉ tài khoản Thanh toán có cảnh báo số dư thấp");
        }
        long threshold = threshold(lowBalanceDong);
        db.update("""
                INSERT INTO notification_settings(wallet_id,low_balance_dong) VALUES (?,?)
                ON CONFLICT (wallet_id) DO UPDATE SET low_balance_dong=EXCLUDED.low_balance_dong,
                    low_balance_alerted=false,updated_at=now()
                """, wallet.id(), threshold);
        lowBalance.evaluate(wallet.id());
        return new SettingsView(wallet.id(), threshold);
    }

    private static long threshold(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Ngưỡng phải là số nguyên VND");
        }
        long value = node.longValue();
        if (value < 0 || value > RequestChecks.MAX_AMOUNT_DONG) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "Ngưỡng phải từ 0 (tắt cảnh báo) đến 1000000000000 VND");
        }
        return value;
    }

    public record NotificationView(UUID id, UUID accountId, String type, String title, String body,
                                   Instant createdAt, boolean read) {}

    public record NotificationPage(List<NotificationView> items, int page, int size, long total, long unread) {}

    public record SettingsView(UUID accountId, long lowBalanceDong) {}
}
