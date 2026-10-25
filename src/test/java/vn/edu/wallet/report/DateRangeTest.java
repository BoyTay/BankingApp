package vn.edu.wallet.report;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import vn.edu.wallet.api.ApiException;

class DateRangeTest {
    @Test void singleDayIsValid() {
        assertEquals(new DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)),
                DateRange.parse("2026-01-01", "2026-01-01"));
    }

    @Test void spanLimitIs366DaysInclusive() {
        assertDoesNotThrow(() -> DateRange.parse("2026-01-01", "2026-12-31")); // 364 days apart
        assertDoesNotThrow(() -> DateRange.parse("2026-01-01", "2027-01-01")); // 365 days apart
        assertInvalid("2026-01-01", "2027-01-02");                             // 366 days apart
    }

    @Test void leapYearBoundary() {
        assertDoesNotThrow(() -> DateRange.parse("2024-01-01", "2024-12-31")); // 365 days apart
        assertInvalid("2024-01-01", "2025-01-01");                             // 366 days apart
    }

    @Test void reversedRangeIsRejected() {
        assertInvalid("2026-01-02", "2026-01-01");
    }

    @Test void missingOrMalformedDatesAreRejected() {
        assertInvalid(null, "2026-01-01");
        assertInvalid("2026-01-01", null);
        assertInvalid("01/01/2026", "2026-01-31");
        assertInvalid("2026-02-30", "2026-03-01");
        assertInvalid("", "");
    }

    private static void assertInvalid(String from, String to) {
        ApiException error = assertThrows(ApiException.class, () -> DateRange.parse(from, to));
        assertEquals("INVALID_DATE_RANGE", error.code());
    }
}
