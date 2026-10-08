package vn.edu.wallet.expense;

import java.util.List;

/** Target interface for different sample CSV structures. */
public interface ExpenseCsvAdapter {
    CsvPreview preview(byte[] utf8Csv);

    default List<ImportedExpense> read(byte[] utf8Csv) {
        return preview(utf8Csv).requireValidRows();
    }
}
