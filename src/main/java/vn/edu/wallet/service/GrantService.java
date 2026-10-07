package vn.edu.wallet.service;

import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.wallet.api.ApiDtos;
import vn.edu.wallet.api.ApiException;
import vn.edu.wallet.api.RequestChecks;

@Service
public class GrantService {
    private final JdbcTemplate db;
    private final WalletQueries wallets;

    public GrantService(JdbcTemplate db, WalletQueries wallets) {
        this.db = db;
        this.wallets = wallets;
    }

    @Transactional
    public ApiDtos.GrantView grant(UUID adminId, ApiDtos.GrantCreate input) {
        if (input == null || input.reason() == null || input.reason().trim().isEmpty()
                || input.reason().trim().length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Lý do cấp tiền không hợp lệ");
        }
        long amount = RequestChecks.amount(input.amountDong());
        WalletQueries.WalletRow wallet = wallets.byCode(input.recipientWalletCode());
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
        db.update("INSERT INTO admin_grants(id,admin_user_id,recipient_wallet_id,amount_dong,reason) VALUES (?,?,?,?,?)",
                grantId, adminId, wallet.id(), amount, input.reason().trim());
        db.update("INSERT INTO ledger_entries(id,wallet_id,grant_id,delta_dong,balance_after_dong) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), wallet.id(), grantId, amount, after);
        Instant at = db.queryForObject("SELECT created_at FROM admin_grants WHERE id=?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), grantId);
        return new ApiDtos.GrantView(grantId, adminId, wallet.code(), amount, after,
                input.reason().trim(), at);
    }
}
