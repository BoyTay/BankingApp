package vn.edu.wallet.expense;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public final class ByCategoryStrategy implements ExpenseAggregationStrategy {
    @Override public List<ExpenseSummary> aggregate(List<ImportedExpense> expenses) {
        Map<String, ExpenseSummary> groups = new TreeMap<>();
        for (ImportedExpense expense : expenses) {
            ExpenseSummary old = groups.getOrDefault(expense.category(),
                    new ExpenseSummary(expense.category(), 0, 0));
            groups.put(expense.category(), new ExpenseSummary(expense.category(),
                    Math.addExact(old.amountDong(), expense.amountDong()), Math.addExact(old.count(), 1)));
        }
        return List.copyOf(groups.values());
    }
}
