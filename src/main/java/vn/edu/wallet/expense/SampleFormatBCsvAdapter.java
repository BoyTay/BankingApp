package vn.edu.wallet.expense;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class SampleFormatBCsvAdapter implements ExpenseCsvAdapter {
    @Override public List<ImportedExpense> read(byte[] utf8Csv) {
        return ExpenseCsvParsing.parse(utf8Csv, ';',
                List.of("Ngày GD", "Nội dung", "Nhóm", "Số tiền"),
                ExpenseCsvParsing.dateB(), ExpenseCsvParsing::amountB);
    }
}
