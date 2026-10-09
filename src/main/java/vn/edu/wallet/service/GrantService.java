package vn.edu.wallet.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.wallet.api.ApiDtos;
import vn.edu.wallet.api.ApiException;
import vn.edu.wallet.api.RequestChecks;
import vn.edu.wallet.account.AccountPolicies;
import vn.edu.wallet.notify.EventSubject;
import vn.edu.wallet.notify.WalletEvent;

@Service
public class GrantService {
    private final JdbcTemplate db;
    private final WalletQueries wallets;
    private final AccountPolicies accountPolicies;
    private final EventSubject events;

    public GrantService(JdbcTemplate db, WalletQueries wallets, AccountPolicies accountPolicies,
                        EventSubject events) {
        this.db = db;
        this.wallets = wallets;
        this.accountPolicies = accountPolicies;
        this.events = events;
    }

    @Transactional
    public GrantResult grant(UUID adminId, ApiDtos.GrantCreate input) {
        if (input == null || input.requestKey() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Thiếu mã yêu cầu");
        }
        // Serialize even before the first INSERT. The unique constraint remains the database backstop.
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))",
                adminId + ":grant:" + input.requestKey());
        List<ApiDtos.GrantView> prior = db.query("""
                SELECT g.id,g.request_key,g.admin_user_id,g.amount_dong,g.reason,g.created_at,
                       w.wallet_code,l.balance_after_dong
                FROM admin_grants g JOIN wallets w ON w.id=g.recipient_wallet_id
                JOIN ledger_entries l ON l.grant_id=g.id
                WHERE g.admin_user_id=? AND g.request_key=?
                """, (rs, row) -> new ApiDtos.GrantView(
                rs.getObject("id", UUID.class), rs.getObject("request_key", UUID.class),
                rs.getObject("admin_user_id", UUID.class), rs.getString("wallet_code"),
                rs.getLong("amount_dong"), rs.getLong("balance_after_dong"),
                rs.getString("reason"), rs.getTimestamp("created_at").toInstant()),
                adminId, input.requestKey());
        if (!prior.isEmpty()) {
            ApiDtos.GrantView receipt = prior.getFirst();
            boolean sameRecipient = input.recipientWalletCode() != null
                    && receipt.recipientWalletCode().equalsIgnoreCase(input.recipientWalletCode().trim());
            boolean sameAmount = input.amountDong() != null && input.amountDong().isIntegralNumber()
                    && input.amountDong().canConvertToLong()
                    && receipt.amountDong() == input.amountDong().longValue();
            boolean sameReason = input.reason() != null && receipt.reason().equals(input.reason().trim());
            if (sameRecipient && sameAmount && sameReason) return new GrantResult(receipt, true);
            throw new ApiException(HttpStatus.CONFLICT, "GRANT_KEY_CONFLICT",
                    "Mã yêu cầu đã được dùng cho khoản cấp tiền khác");
        }
        if (input.reason() == null || input.reason().trim().isEmpty()
                || input.reason().trim().length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Lý do cấp tiền không hợp lệ");
        }
        long amount = RequestChecks.amount(input.amountDong());
        WalletQueries.WalletRow wallet = wallets.byCode(input.recipientWalletCode());
        if (!"ACTIVE".equals(wallet.status())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Không tìm thấy ví");
        }
        accountPolicies.forType(wallet.accountType()).requireAdminGrant();
        long balance = wallets.lockBalance(wallet.id());
        long after;
        try {
            after = Math.addExact(balance, amount);
        } catch (ArithmeticException ex) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BALANCE_OVERFLOW",
                    "Số dư vượt giới hạn");
        }
        UUID grantId = UUID.randomUUID();
        db.update("UPDATE wallets SET balance_dong=? WHERE id=?", after, wallet.id());
        db.update("INSERT INTO admin_grants(id,admin_user_id,recipient_wallet_id,request_key,amount_dong,reason) VALUES (?,?,?,?,?,?)",
                grantId, adminId, wallet.id(), input.requestKey(), amount, input.reason().trim());
        db.update("INSERT INTO ledger_entries(id,wallet_id,grant_id,delta_dong,balance_after_dong) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), wallet.id(), grantId, amount, after);
        Instant at = db.queryForObject("SELECT created_at FROM admin_grants WHERE id=?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), grantId);
        events.publish(new WalletEvent.GrantReceived(wallet.ownerId(), wallet.id(), amount, after,
                input.reason().trim()));
        return new GrantResult(new ApiDtos.GrantView(grantId, input.requestKey(), adminId, wallet.code(), amount, after,
                input.reason().trim(), at), false);
    }

    public record GrantResult(ApiDtos.GrantView receipt, boolean replayed) {}
}
