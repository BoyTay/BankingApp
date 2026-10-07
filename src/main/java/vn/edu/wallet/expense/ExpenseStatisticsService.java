package vn.edu.wallet.expense;

import java.sql.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.wallet.api.ApiDtos;
import vn.edu.wallet.api.ApiException;
import vn.edu.wallet.report.DateRange;

@Service
public class ExpenseStatisticsService {
    private static final int MAX_ROWS = 100_000;
    private final JdbcTemplate db;
    private final ByCategoryStrategy byCategory;
    private final ByMonthStrategy byMonth;

    public ExpenseStatisticsService(JdbcTemplate db, ByCategoryStrategy byCategory, ByMonthStrategy byMonth) {
        this.db = db;
        this.byCategory = byCategory;
        this.byMonth = byMonth;
    }

    @Transactional(readOnly = true)
    public ApiDtos.ExpenseStatsView stats(UUID userId, String from, String to, String groupBy) {
        DateRange range = DateRange.parse(from, to);
        ExpenseAggregationStrategy strategy = switch (groupBy == null ? "" : groupBy) {
            case "category" -> byCategory;
            case "month" -> byMonth;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GROUP_BY",
                    "groupBy phải là category hoặc month");
        };
        List<ImportedExpense> expenses = db.query("""
                SELECT e.source_row,e.spent_on,e.description,e.category,e.amount_dong
                FROM imported_expenses e JOIN import_batches b ON b.id=e.batch_id
                WHERE b.owner_user_id=? AND e.spent_on BETWEEN ? AND ?
                ORDER BY e.spent_on,e.id LIMIT ?
                """, (rs, row) -> new ImportedExpense(rs.getInt("source_row"),
                rs.getDate("spent_on").toLocalDate(), rs.getString("description"),
                rs.getString("category"), rs.getLong("amount_dong")),
                userId, Date.valueOf(range.from()), Date.valueOf(range.to()), MAX_ROWS + 1);
        if (expenses.size() > MAX_ROWS) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "STATS_TOO_LARGE",
                    "Thống kê vượt quá 100000 khoản chi");
        }
        try {
            List<ExpenseSummary> summaries = summarize(strategy, expenses);
            long totalAmount = 0;
            long totalCount = 0;
            for (ExpenseSummary summary : summaries) {
                totalAmount = Math.addExact(totalAmount, summary.amountDong());
                totalCount = Math.addExact(totalCount, summary.count());
            }
            return new ApiDtos.ExpenseStatsView(groupBy, range.from().toString(), range.to().toString(),
                    totalAmount, totalCount, summaries.stream().map(s ->
                    new ApiDtos.ExpenseSummaryView(s.key(), s.amountDong(), s.count())).toList());
        } catch (ArithmeticException ex) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AGGREGATE_OVERFLOW",
                    "Tổng thống kê vượt giới hạn số nguyên");
        }
    }

    public List<ExpenseSummary> summarize(ExpenseAggregationStrategy strategy, List<ImportedExpense> expenses) {
        return strategy.aggregate(expenses);
    }
}
