package vn.edu.wallet.expense;

import java.util.List;

/** Target interface for different sample CSV structures. */
public interface ExpenseCsvAdapter {
    List<ImportedExpense> read(byte[] utf8Csv);
}
