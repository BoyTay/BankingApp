package vn.edu.wallet.expense;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpenseStrategyTest {
    private static ImportedExpense e(int row, String date, String category, long amount) {
        return new ImportedExpense(row, LocalDate.parse(date), "d", category, amount);
    }

    private final List<ImportedExpense> data = List.of(
            e(2, "2026-01-31", "Food", 100), e(3, "2026-02-01", "Food", 200),
            e(4, "2026-02-15", "Transport", 50), e(5, "2025-12-31", "Food", 1));

    @Test void byCategorySumsAndCountsSortedByKey() {
        assertEquals(List.of(new ExpenseSummary("Food", 301, 3), new ExpenseSummary("Transport", 50, 1)),
                new ByCategoryStrategy().aggregate(data));
    }

    @Test void byMonthSplitsAtMonthBoundariesSortedChronologically() {
        assertEquals(List.of(new ExpenseSummary("2025-12", 1, 1), new ExpenseSummary("2026-01", 100, 1),
                new ExpenseSummary("2026-02", 250, 2)), new ByMonthStrategy().aggregate(data));
    }

    @Test void bothStrategiesGiveTheSameGrandTotal() {
        long byCategory = new ByCategoryStrategy().aggregate(data).stream().mapToLong(ExpenseSummary::amountDong).sum();
        long byMonth = new ByMonthStrategy().aggregate(data).stream().mapToLong(ExpenseSummary::amountDong).sum();
        assertEquals(351, byCategory);
        assertEquals(byCategory, byMonth);
    }

    @Test void emptyInputGivesEmptyResult() {
        assertTrue(new ByCategoryStrategy().aggregate(List.of()).isEmpty());
        assertTrue(new ByMonthStrategy().aggregate(List.of()).isEmpty());
    }

    @Test void sumOverflowThrowsInsteadOfWrapping() {
        var big = List.of(e(2, "2026-01-01", "A", Long.MAX_VALUE), e(3, "2026-01-02", "A", 1));
        assertThrows(ArithmeticException.class, () -> new ByCategoryStrategy().aggregate(big));
    }

    @Test void sqlKeyExpressionsAreConstants() {
        assertEquals("e.category", new ByCategoryStrategy().sqlKeyExpression());
        assertEquals("to_char(e.spent_on, 'YYYY-MM')", new ByMonthStrategy().sqlKeyExpression());
    }
}
