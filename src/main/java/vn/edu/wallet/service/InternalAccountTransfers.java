package vn.edu.wallet.service;

import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.edu.wallet.api.ApiException;
import vn.edu.wallet.core.Money;
import vn.edu.wallet.core.TransferErrorCode;
import vn.edu.wallet.core.TransferException;
import vn.edu.wallet.core.TransferRequest;
import vn.edu.wallet.core.TransferRules;

/** Posts a transfer between two already authorized accounts inside the caller's transaction. */
@Component
public class InternalAccountTransfers {
    private final JdbcTemplate db;
    private final WalletQueries wallets;

    public InternalAccountTransfers(JdbcTemplate db, WalletQueries wallets) {
        this.db = db;
        this.wallets = wallets;
    }

    public UUID move(UUID sourceId, UUID targetId, long amount, UUID requestKey) {
        if (amount <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Số tiền phải lớn hơn 0");
        UUID first = sourceId.toString().compareTo(targetId.toString()) <= 0 ? sourceId : targetId;
        UUID second = first.equals(sourceId) ? targetId : sourceId;
        long firstBalance = wallets.lockBalance(first);
        long secondBalance = wallets.lockBalance(second);
        long sourceBalance = first.equals(sourceId) ? firstBalance : secondBalance;
        long targetBalance = first.equals(targetId) ? firstBalance : secondBalance;
        UUID transferId = UUID.randomUUID();
        vn.edu.wallet.core.TransferReceipt result;
        try {
            result = TransferRules.decide(new TransferRequest(requestKey, sourceId, targetId, new Money(amount)),
                    new Money(sourceBalance), new Money(targetBalance), null, transferId, Instant.now());
        } catch (TransferException ex) {
            HttpStatus status = ex.code() == TransferErrorCode.INSUFFICIENT_FUNDS
                    ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_REQUEST;
            throw new ApiException(status, ex.code().name(), ex.getMessage());
        } catch (ArithmeticException ex) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BALANCE_OVERFLOW", "Số dư vượt giới hạn");
        }
        db.update("UPDATE wallets SET balance_dong=? WHERE id=?", result.senderBalanceAfter().dong(), sourceId);
        db.update("UPDATE wallets SET balance_dong=? WHERE id=?", result.recipientBalanceAfter().dong(), targetId);
        db.update("""
                INSERT INTO transfers(id,sender_wallet_id,recipient_wallet_id,request_key,amount_dong,
                    sender_balance_after_dong,recipient_balance_after_dong)
                VALUES (?,?,?,?,?,?,?)
                """, transferId, sourceId, targetId, requestKey, amount,
                result.senderBalanceAfter().dong(), result.recipientBalanceAfter().dong());
        db.update("INSERT INTO ledger_entries(id,wallet_id,transfer_id,delta_dong,balance_after_dong) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), sourceId, transferId, -amount, result.senderBalanceAfter().dong());
        db.update("INSERT INTO ledger_entries(id,wallet_id,transfer_id,delta_dong,balance_after_dong) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), targetId, transferId, amount, result.recipientBalanceAfter().dong());
        return transferId;
    }
}
