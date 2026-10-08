package vn.edu.wallet.expense;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public final class ByMonthStrategy implements ExpenseAggregationStrategy {
    @Override public String sqlKeyExpression() { return "to_char(e.spent_on, 'YYYY-MM')"; }

    @Override public List<ExpenseSummary> aggregate(List<ImportedExpense> expenses) {
        Map<YearMonth, ExpenseSummary> groups = new TreeMap<>();
        for (ImportedExpense expense : expenses) {
            YearMonth month = YearMonth.from(expense.spentOn());
            ExpenseSummary old = groups.getOrDefault(month, new ExpenseSummary(month.toString(), 0, 0));
            groups.put(month, new ExpenseSummary(month.toString(),
                    Math.addExact(old.amountDong(), expense.amountDong()), Math.addExact(old.count(), 1)));
        }
        return List.copyOf(groups.values());
    }
}
