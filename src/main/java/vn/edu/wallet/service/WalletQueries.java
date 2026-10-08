package vn.edu.wallet.service;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.edu.wallet.api.ApiDtos;
import vn.edu.wallet.api.ApiException;
import vn.edu.wallet.api.RequestChecks;

@Component
public class WalletQueries {
    private final JdbcTemplate db;
    public WalletQueries(JdbcTemplate db) { this.db = db; }

    public WalletRow ownedBy(UUID userId) {
        return ownedBy(userId, null);
    }

    public WalletRow ownedBy(UUID userId, UUID accountId) {
        String where = accountId == null
                ? "owner_id=? AND is_default=true AND account_status='ACTIVE'"
                : "owner_id=? AND id=?";
        Object[] args = accountId == null ? new Object[] {userId} : new Object[] {userId, accountId};
        List<WalletRow> rows = db.query("SELECT id,owner_id,wallet_code,balance_dong,account_type,account_status FROM wallets WHERE " + where,
                (rs, row) -> new WalletRow(rs.getObject("id", UUID.class),
                        rs.getObject("owner_id", UUID.class), rs.getString("wallet_code"),
                        rs.getLong("balance_dong"), rs.getString("account_type"), rs.getString("account_status")), args);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Không tìm thấy ví");
        return rows.getFirst();
    }

    public WalletRow byCode(String input) {
        String code = RequestChecks.walletCode(input);
        List<WalletRow> rows = db.query("SELECT id,owner_id,wallet_code,balance_dong,account_type,account_status FROM wallets WHERE wallet_code=?",
                (rs, row) -> new WalletRow(rs.getObject("id", UUID.class),
                        rs.getObject("owner_id", UUID.class), rs.getString("wallet_code"),
                        rs.getLong("balance_dong"), rs.getString("account_type"), rs.getString("account_status")), code);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Không tìm thấy ví");
        return rows.getFirst();
    }

    public long lockBalance(UUID walletId) {
        Long value = db.queryForObject("SELECT balance_dong FROM wallets WHERE id=? FOR UPDATE", Long.class, walletId);
        if (value == null) throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Không tìm thấy ví");
        return value;
    }

    public ApiDtos.WalletView mine(UUID userId) {
        WalletRow wallet = ownedBy(userId);
        return new ApiDtos.WalletView(wallet.id(), wallet.code(), wallet.balanceDong());
    }

    public ApiDtos.WalletLookup lookup(String code) {
        List<ApiDtos.WalletLookup> rows = db.query("""
                SELECT w.id,w.wallet_code,u.display_name FROM wallets w
                JOIN app_users u ON u.id=w.owner_id WHERE w.wallet_code=?
                """, (rs, row) -> new ApiDtos.WalletLookup(rs.getObject("id", UUID.class),
                rs.getString("wallet_code"), rs.getString("display_name")), RequestChecks.walletCode(code));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Không tìm thấy ví");
        return rows.getFirst();
    }

    public record WalletRow(UUID id, UUID ownerId, String code, long balanceDong,
                            String accountType, String status) {}
}
