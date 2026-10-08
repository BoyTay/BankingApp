package vn.edu.wallet.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.wallet.api.ApiDtos;
import vn.edu.wallet.api.ApiException;

@Service
public class ReconciliationService {
    private final JdbcTemplate db;

    public ReconciliationService(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApiDtos.ReconciliationView inspect(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Phân trang không hợp lệ");
        }
        List<ReportRow> rows = db.query("""
                WITH wallet_totals AS MATERIALIZED (
                    SELECT w.id, w.wallet_code, w.balance_dong,
                           COALESCE(SUM(l.delta_dong), 0) AS ledger_balance
                    FROM wallets w LEFT JOIN ledger_entries l ON l.wallet_id = w.id
                    GROUP BY w.id
                ), mismatches AS MATERIALIZED (
                    SELECT * FROM wallet_totals
                    WHERE balance_dong::numeric <> ledger_balance
                )
                SELECT (SELECT count(*) FROM wallet_totals) AS checked_wallets,
                       (SELECT count(*) FROM mismatches) AS mismatch_count,
                       transaction_timestamp() AS checked_at,
                       issue.id AS wallet_id, issue.wallet_code,
                       issue.balance_dong, issue.ledger_balance
                FROM (SELECT 1) seed
                LEFT JOIN (
                    SELECT * FROM mismatches ORDER BY wallet_code LIMIT ? OFFSET ?
                ) issue ON true
                ORDER BY issue.wallet_code
                """, ReconciliationService::map, size, (long) page * size);
        ReportRow first = rows.getFirst();
        List<ApiDtos.BalanceMismatchView> items = new ArrayList<>();
        for (ReportRow row : rows) {
            if (row.walletId() == null) continue;
            BigDecimal actual = BigDecimal.valueOf(row.actualBalance());
            items.add(new ApiDtos.BalanceMismatchView(row.walletId(), row.walletCode(),
                    actual.toPlainString(), row.ledgerBalance().toPlainString(),
                    actual.subtract(row.ledgerBalance()).toPlainString()));
        }
        return new ApiDtos.ReconciliationView(first.checkedAt(), first.checkedWallets(),
                first.mismatchCount(), page, size, List.copyOf(items));
    }

    private static ReportRow map(ResultSet rs, int ignored) throws SQLException {
        UUID walletId = rs.getObject("wallet_id", UUID.class);
        return new ReportRow(rs.getTimestamp("checked_at").toInstant(),
                rs.getLong("checked_wallets"), rs.getLong("mismatch_count"),
                walletId, rs.getString("wallet_code"), rs.getLong("balance_dong"),
                walletId == null ? null : rs.getBigDecimal("ledger_balance"));
    }

    private record ReportRow(Instant checkedAt, long checkedWallets, long mismatchCount,
                             UUID walletId, String walletCode, long actualBalance,
                             BigDecimal ledgerBalance) {}
}
