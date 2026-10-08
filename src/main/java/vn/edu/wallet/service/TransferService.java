package vn.edu.wallet.service;

import java.sql.ResultSet;
import java.sql.SQLException;
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
import vn.edu.wallet.core.Money;
import vn.edu.wallet.core.TransferException;
import vn.edu.wallet.core.TransferRequest;
import vn.edu.wallet.core.TransferRules;

@Service
public class TransferService {
    private static final String RECEIPT_SELECT = """
            SELECT t.id,t.request_key,t.amount_dong,t.sender_balance_after_dong,
                   t.recipient_balance_after_dong,t.created_at,
                   sw.wallet_code AS sender_code,rw.wallet_code AS recipient_code,
                   t.sender_wallet_id,t.recipient_wallet_id
            FROM transfers t JOIN wallets sw ON sw.id=t.sender_wallet_id
            JOIN wallets rw ON rw.id=t.recipient_wallet_id
            """;
    private final JdbcTemplate db;
    private final WalletQueries wallets;
    private final TransferWriteHook hook;
    private final AccountPolicies accountPolicies;

    public TransferService(JdbcTemplate db, WalletQueries wallets, TransferWriteHook hook,
                           AccountPolicies accountPolicies) {
        this.db = db;
        this.wallets = wallets;
        this.hook = hook;
        this.accountPolicies = accountPolicies;
    }

    @Transactional
    public TransferResult transfer(UUID userId, ApiDtos.TransferCreate input) {
        if (input == null || input.requestKey() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Thiếu mã yêu cầu");
        }
        WalletQueries.WalletRow sender = wallets.ownedBy(userId, input.sourceAccountId());
        accountPolicies.forType(sender.accountType()).requireOutgoing();
        String lockKey = sender.id() + ":" + input.requestKey();
        // PostgreSQL transaction advisory lock serializes the same sender/requestKey,
        // including the interval before the first INSERT has committed.
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))", lockKey);
        List<ApiDtos.TransferView> prior = db.query(RECEIPT_SELECT +
                " WHERE t.sender_wallet_id=? AND t.request_key=?",
                (rs, row) -> receipt(rs, sender.id()),
                sender.id(), input.requestKey());
        if (!prior.isEmpty()) {
            ApiDtos.TransferView receipt = prior.getFirst();
            boolean sameRecipient = input.recipientWalletCode() != null
                    && receipt.recipientWalletCode().equalsIgnoreCase(input.recipientWalletCode().trim());
            boolean sameAmount = input.amountDong() != null && input.amountDong().isIntegralNumber()
                    && input.amountDong().canConvertToLong()
                    && receipt.amountDong() == input.amountDong().longValue();
            if (sameRecipient && sameAmount) {
                return new TransferResult(receipt, true);
            }
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "Mã yêu cầu đã được dùng cho giao dịch khác");
        }
        long amount = RequestChecks.amount(input.amountDong());
        String recipientCode = RequestChecks.walletCode(input.recipientWalletCode());
        WalletQueries.WalletRow recipient = wallets.byCode(recipientCode);
        if (!"ACTIVE".equals(recipient.status())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Không tìm thấy ví");
        }
        accountPolicies.forType(recipient.accountType()).requireIncoming();
        if (sender.id().equals(recipient.id())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_TRANSFER", "Không thể chuyển tiền cho chính mình");
        }
        UUID first = sender.id().toString().compareTo(recipient.id().toString()) <= 0 ? sender.id() : recipient.id();
        UUID second = first.equals(sender.id()) ? recipient.id() : sender.id();
        long firstBalance = wallets.lockBalance(first);
        long secondBalance = wallets.lockBalance(second);
        long senderBalance = first.equals(sender.id()) ? firstBalance : secondBalance;
        long recipientBalance = first.equals(recipient.id()) ? firstBalance : secondBalance;
        UUID transferId = UUID.randomUUID();
        vn.edu.wallet.core.TransferReceipt decision;
        try {
            decision = TransferRules.decide(new TransferRequest(input.requestKey(), sender.id(), recipient.id(),
                    new Money(amount)), new Money(senderBalance), new Money(recipientBalance),
                    null, transferId, Instant.now());
        } catch (TransferException ex) {
            HttpStatus status = ex.code() == vn.edu.wallet.core.TransferErrorCode.INSUFFICIENT_FUNDS
                    ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_REQUEST;
            throw new ApiException(status, ex.code().name(), ex.getMessage());
        } catch (ArithmeticException ex) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BALANCE_OVERFLOW",
                    "Số dư đích vượt giới hạn");
        }
        db.update("UPDATE wallets SET balance_dong=? WHERE id=?", decision.senderBalanceAfter().dong(), sender.id());
        hook.afterDebit();
        db.update("UPDATE wallets SET balance_dong=? WHERE id=?", decision.recipientBalanceAfter().dong(), recipient.id());
        db.update("""
                INSERT INTO transfers(id,sender_wallet_id,recipient_wallet_id,request_key,amount_dong,
                    sender_balance_after_dong,recipient_balance_after_dong)
                VALUES (?,?,?,?,?,?,?)
                """, transferId, sender.id(), recipient.id(), input.requestKey(), amount,
                decision.senderBalanceAfter().dong(), decision.recipientBalanceAfter().dong());
        db.update("INSERT INTO ledger_entries(id,wallet_id,transfer_id,delta_dong,balance_after_dong) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), sender.id(), transferId, -amount, decision.senderBalanceAfter().dong());
        db.update("INSERT INTO ledger_entries(id,wallet_id,transfer_id,delta_dong,balance_after_dong) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), recipient.id(), transferId, amount, decision.recipientBalanceAfter().dong());
        return new TransferResult(byIdForWallet(transferId, sender.id()), false);
    }

    public ApiDtos.TransferView receiptForUser(UUID transferId, UUID userId) {
        List<UUID> accountIds = db.query("""
                SELECT CASE WHEN sw.owner_id=? THEN sw.id ELSE rw.id END AS account_id
                FROM transfers t JOIN wallets sw ON sw.id=t.sender_wallet_id
                JOIN wallets rw ON rw.id=t.recipient_wallet_id
                WHERE t.id=? AND (sw.owner_id=? OR rw.owner_id=?)
                """, (rs, row) -> rs.getObject("account_id", UUID.class), userId, transferId, userId, userId);
        if (accountIds.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Không tìm thấy giao dịch");
        }
        return byIdForWallet(transferId, accountIds.getFirst());
    }

    public ApiDtos.TransferPage history(UUID userId, int page, int size) {
        return history(userId, null, page, size);
    }

    public ApiDtos.TransferPage history(UUID userId, UUID accountId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Phân trang không hợp lệ");
        }
        UUID walletId = wallets.ownedBy(userId, accountId).id();
        long offset = (long) page * size;
        Long count = db.queryForObject("SELECT count(*) FROM transfers WHERE sender_wallet_id=? OR recipient_wallet_id=?",
                Long.class, walletId, walletId);
        List<ApiDtos.TransferView> items = db.query(RECEIPT_SELECT + """
                 WHERE t.sender_wallet_id=? OR t.recipient_wallet_id=?
                 ORDER BY t.created_at DESC,t.id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> receipt(rs, walletId), walletId, walletId, size, offset);
        return new ApiDtos.TransferPage(items, page, size, count == null ? 0 : count);
    }

    private ApiDtos.TransferView byIdForWallet(UUID id, UUID walletId) {
        List<ApiDtos.TransferView> rows = db.query(RECEIPT_SELECT +
                " WHERE t.id=? AND (t.sender_wallet_id=? OR t.recipient_wallet_id=?)",
                (rs, row) -> receipt(rs, walletId), id, walletId, walletId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Không tìm thấy giao dịch");
        }
        return rows.getFirst();
    }

    private static ApiDtos.TransferView receipt(ResultSet rs, UUID viewerWalletId) throws SQLException {
        boolean outgoing = viewerWalletId.equals(rs.getObject("sender_wallet_id", UUID.class));
        return new ApiDtos.TransferView(rs.getObject("id", UUID.class),
                rs.getObject("request_key", UUID.class), rs.getString("sender_code"),
                rs.getString("recipient_code"), rs.getLong("amount_dong"),
                outgoing ? "OUTGOING" : "INCOMING",
                rs.getLong(outgoing ? "sender_balance_after_dong" : "recipient_balance_after_dong"),
                rs.getTimestamp("created_at").toInstant());
    }

    public record TransferResult(ApiDtos.TransferView receipt, boolean replayed) {}
}
