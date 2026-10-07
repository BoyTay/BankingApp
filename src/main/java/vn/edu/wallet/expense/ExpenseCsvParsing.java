package vn.edu.wallet.expense;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import vn.edu.wallet.api.ApiException;
import vn.edu.wallet.api.RequestChecks;

final class ExpenseCsvParsing {
    private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    private static final LocalDate LATEST = LocalDate.of(2100, 12, 31);
    private ExpenseCsvParsing() {}

    static List<ImportedExpense> parse(byte[] bytes, char delimiter, List<String> header,
                                       Function<String, LocalDate> dateParser,
                                       Function<String, Long> amountParser) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw invalidCsv("Tệp không phải UTF-8 hợp lệ");
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        try (CSVParser parser = CSVParser.parse(text,
                CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setIgnoreEmptyLines(false).get())) {
            List<CSVRecord> records = parser.getRecords();
            if (records.isEmpty() || records.getFirst().size() != header.size()) {
                throw invalidCsv("Header CSV không đúng định dạng");
            }
            for (int i = 0; i < header.size(); i++) {
                if (!header.get(i).equals(records.getFirst().get(i))) {
                    throw invalidCsv("Header CSV không đúng định dạng");
                }
            }
            if (records.size() <= 1) throw invalidCsv("CSV không có dòng dữ liệu");
            if (records.size() - 1 > 5_000) throw invalidCsv("CSV vượt quá 5000 dòng dữ liệu");
            List<ImportedExpense> result = new ArrayList<>();
            for (int i = 1; i < records.size(); i++) {
                CSVRecord row = records.get(i);
                int line = i + 1;
                if (row.size() != 4) throw invalidCsv("Dòng " + line + ": cần đúng 4 cột");
                LocalDate date;
                try {
                    date = dateParser.apply(row.get(0).trim());
                } catch (DateTimeParseException ex) {
                    throw invalidCsv("Dòng " + line + ": ngày không hợp lệ");
                }
                if (date.isBefore(EARLIEST) || date.isAfter(LATEST)) {
                    throw invalidCsv("Dòng " + line + ": ngày ngoài khoảng 2000–2100");
                }
                String description = row.get(1).trim();
                String category = row.get(2).trim();
                if (description.isEmpty() || description.length() > 500
                        || category.isEmpty() || category.length() > 100) {
                    throw invalidCsv("Dòng " + line + ": nội dung hoặc danh mục không hợp lệ");
                }
                long amount;
                try {
                    amount = amountParser.apply(row.get(3).trim());
                } catch (NumberFormatException ex) {
                    throw invalidAmount(line);
                }
                if (amount < 1 || amount > RequestChecks.MAX_AMOUNT_DONG) throw invalidAmount(line);
                result.add(new ImportedExpense(line, date, description, category, amount));
            }
            return List.copyOf(result);
        } catch (IOException | IllegalArgumentException ex) {
            throw invalidCsv("Cấu trúc CSV không hợp lệ");
        }
    }

    static long amountA(String value) {
        if (!value.matches("(?:0|[1-9][0-9]*)(?:\\.00)?")) throw new NumberFormatException();
        return Long.parseLong(value.endsWith(".00") ? value.substring(0, value.length() - 3) : value);
    }

    static long amountB(String value) {
        if (!value.matches("(?:0|[1-9][0-9]*|[1-9][0-9]{0,2}(?:\\.[0-9]{3})+)(?:,00)?")) {
            throw new NumberFormatException();
        }
        String whole = value.endsWith(",00") ? value.substring(0, value.length() - 3) : value;
        return Long.parseLong(whole.replace(".", ""));
    }

    static Function<String, LocalDate> dateB() {
        DateTimeFormatter format = DateTimeFormatter.ofPattern("dd/MM/uuuu")
                .withResolverStyle(java.time.format.ResolverStyle.STRICT);
        return value -> LocalDate.parse(value, format);
    }

    private static ApiException invalidCsv(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CSV", message);
    }

    private static ApiException invalidAmount(int line) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                "Dòng " + line + ": số tiền không hợp lệ");
    }
}
