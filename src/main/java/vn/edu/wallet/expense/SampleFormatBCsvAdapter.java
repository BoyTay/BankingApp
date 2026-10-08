package vn.edu.wallet.expense;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class SampleFormatBCsvAdapter implements ExpenseCsvAdapter {
    @Override public CsvPreview preview(byte[] utf8Csv) {
        String firstLine = new String(utf8Csv, StandardCharsets.UTF_8).split("\\R", 2)[0];
        if (firstLine.startsWith("\uFEFF")) firstLine = firstLine.substring(1);
        if (firstLine.equals("Ngày GD;Nội dung;Nhóm;Số tiền")) {
            return ExpenseCsvParsing.preview(utf8Csv, ';',
                    List.of("Ngày GD", "Nội dung", "Nhóm", "Số tiền"),
                    ExpenseCsvParsing.dateB(), ExpenseCsvParsing::amountB);
        }
        return ExpenseCsvParsing.preview(utf8Csv, ',',
                List.of("Ngày", "Nội dung", "Danh mục", "Số tiền"),
                LocalDate::parse, ExpenseCsvParsing::amountA);
    }
}
