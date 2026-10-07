package vn.edu.wallet.expense;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class SampleFormatACsvAdapter implements ExpenseCsvAdapter {
    @Override public List<ImportedExpense> read(byte[] utf8Csv) {
        return ExpenseCsvParsing.parse(utf8Csv, ',',
                List.of("date", "description", "category", "amount_vnd"),
                LocalDate::parse, ExpenseCsvParsing::amountA);
    }
}
