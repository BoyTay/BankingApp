package vn.edu.wallet.expense;

import java.util.List;

public interface ExpenseAggregationStrategy {
    List<ExpenseSummary> aggregate(List<ImportedExpense> expenses);
}
