package vn.edu.wallet.expense;

import java.util.List;
import org.springframework.http.HttpStatus;
import vn.edu.wallet.api.ApiException;

public record CsvPreview(int rowCount, List<ImportedExpense> validRows, List<RowError> errors) {
    public CsvPreview {
        validRows = List.copyOf(validRows);
        errors = List.copyOf(errors);
    }

    public boolean canImport() {
        return rowCount > 0 && errors.isEmpty();
    }

    public List<ImportedExpense> requireValidRows() {
        if (!errors.isEmpty()) {
            RowError first = errors.getFirst();
            throw new ApiException(HttpStatus.BAD_REQUEST, first.code(), first.message());
        }
        return validRows;
    }

    public record RowError(Integer sourceRow, String code, String message) {}
}
