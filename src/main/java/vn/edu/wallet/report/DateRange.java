package vn.edu.wallet.report;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import vn.edu.wallet.api.ApiException;

public record DateRange(LocalDate from, LocalDate to) {
    public static DateRange parse(String from, String to) {
        try {
            if (from == null || to == null) throw new DateTimeParseException("missing", "", 0);
            LocalDate start = LocalDate.parse(from);
            LocalDate end = LocalDate.parse(to);
            if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= 366) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                        "Khoảng ngày phải tăng dần và không quá 366 ngày");
            }
            return new DateRange(start, end);
        } catch (DateTimeParseException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "Ngày phải theo định dạng yyyy-MM-dd");
        }
    }
}
