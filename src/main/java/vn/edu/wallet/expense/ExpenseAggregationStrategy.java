package vn.edu.wallet.expense;

import java.util.List;

public interface ExpenseAggregationStrategy {
    /** Constant SQL expression chosen by a trusted strategy, never from the request. */
    String sqlKeyExpression();
    List<ExpenseSummary> aggregate(List<ImportedExpense> expenses);
}
