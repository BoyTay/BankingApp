package vn.edu.wallet.expense;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.edu.wallet.api.ApiException;

class ExpenseCsvAdapterTest {
    private static final String HEADER_A = "date,description,category,amount_vnd\n";

    private final ExpenseCsvAdapter a = new SampleFormatACsvAdapter();
    private final ExpenseCsvAdapter b = new SampleFormatBCsvAdapter();

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Test void formatAParsesValidRows() {
        var preview = a.preview(utf8(HEADER_A + "2026-01-01,Lunch,Food,12000\n2026-01-02,Bus,Transport,7000.00\n"));
        assertTrue(preview.canImport());
        assertEquals(2, preview.rowCount());
        assertEquals(new ImportedExpense(2, LocalDate.of(2026, 1, 1), "Lunch", "Food", 12_000),
                preview.validRows().getFirst());
        assertEquals(7_000, preview.validRows().get(1).amountDong());
    }

    @Test void formatBAcceptsNewCommaFormatWithVietnameseText() {
        var preview = b.preview(utf8("Ngày,Nội dung,Danh mục,Số tiền\n03-05-2026,Cà phê,Ăn uống,45000\n"));
        assertTrue(preview.canImport());
        assertEquals("Ăn uống", preview.validRows().getFirst().category());
    }

    @Test void formatBAcceptsMonthFirstDates() {
        var preview = b.preview(utf8("Ngày,Nội dung,Danh mục,Số tiền\n"
                + "10/5/2026,Bữa trưa,Ăn uống,45000\n"
                + "10/25/2026,Xăng,Đi lại,75000\n"
                + "10-08-2026,Xe buýt,Đi lại,12000\n"));
        assertTrue(preview.canImport(), preview.errors().toString());
        assertEquals(LocalDate.of(2026, 10, 5), preview.validRows().get(0).spentOn());
        assertEquals(LocalDate.of(2026, 10, 25), preview.validRows().get(1).spentOn());
        assertEquals(LocalDate.of(2026, 10, 8), preview.validRows().get(2).spentOn());
    }

    @Test void formatBRejectsIsoAndDayFirstDates() {
        assertFalse(b.preview(utf8("Ngày,Nội dung,Danh mục,Số tiền\n2026-10-05,A,B,1\n")).canImport());
        assertFalse(b.preview(utf8("Ngày,Nội dung,Danh mục,Số tiền\n25/10/2026,A,B,1\n")).canImport());
    }

    @Test void formatBRejectsImpossibleSlashDate() {
        assertFalse(b.preview(utf8("Ngày,Nội dung,Danh mục,Số tiền\n13/45/2026,A,B,1\n")).canImport());
    }

    @Test void formatBAcceptsLegacySemicolonFormat() {
        var preview = b.preview(utf8("Ngày GD;Nội dung;Nhóm;Số tiền\n05/03/2026;Cà phê;Ăn uống;1.250.000,00\n"));
        assertTrue(preview.canImport(), preview.errors().toString());
        var row = preview.validRows().getFirst();
        assertEquals(LocalDate.of(2026, 3, 5), row.spentOn());
        assertEquals(1_250_000, row.amountDong());
    }

    @Test void bomIsIgnored() {
        assertTrue(a.preview(utf8("﻿" + HEADER_A + "2026-01-01,Lunch,Food,1\n")).canImport());
    }

    @Test void rowErrorsAreReportedPerLineWithoutHidingValidRows() {
        var preview = a.preview(utf8(HEADER_A
                + "2026-01-01,Lunch,Food,12000\n"    // line 2 ok
                + "2026-13-01,Bad date,Food,1\n"     // line 3
                + "2026-01-03,Bad amount,Food,1.5\n" // line 4
                + "2026-01-04,Zero,Food,0\n"         // line 5
                + "1999-12-31,Too old,Food,1\n"      // line 6
                + "2026-01-05,,Food,1\n"             // line 7 empty description
                + "2026-01-06,Short,Food\n"));       // line 8 wrong column count
        assertFalse(preview.canImport());
        assertEquals(1, preview.validRows().size());
        assertEquals(List.of(3, 4, 5, 6, 7, 8),
                preview.errors().stream().map(CsvPreview.RowError::sourceRow).toList());
    }

    @Test void amountBoundaries() {
        assertTrue(a.preview(utf8(HEADER_A + "2026-01-01,x,y,1000000000000\n")).canImport());
        var over = a.preview(utf8(HEADER_A + "2026-01-01,x,y,1000000000001\n"));
        assertFalse(over.canImport());
        assertEquals("INVALID_AMOUNT", over.errors().getFirst().code());
        assertFalse(a.preview(utf8(HEADER_A + "2026-01-01,x,y,-5\n")).canImport());
    }

    @Test void descriptionAndCategoryLengthLimits() {
        assertTrue(a.preview(utf8(HEADER_A + "2026-01-01," + "d".repeat(500) + "," + "c".repeat(100) + ",1\n")).canImport());
        assertFalse(a.preview(utf8(HEADER_A + "2026-01-01," + "d".repeat(501) + ",c,1\n")).canImport());
        assertFalse(a.preview(utf8(HEADER_A + "2026-01-01,d," + "c".repeat(101) + ",1\n")).canImport());
    }

    @Test void fileLevelErrors() {
        assertFileError(a.preview(utf8("")));
        assertFileError(a.preview(utf8("wrong,header,here,now\n2026-01-01,x,y,1\n")));
        assertFileError(a.preview(utf8(HEADER_A)));
        assertFileError(a.preview(new byte[] {(byte) 0xC3, (byte) 0x28})); // invalid UTF-8
    }

    @Test void rowCountLimitIs5000() {
        assertFileError(a.preview(utf8(rows(5_001))));
        assertTrue(a.preview(utf8(rows(5_000))).canImport());
    }

    @Test void formulaLikeTextIsKeptAsPlainData() {
        var preview = a.preview(utf8(HEADER_A + "2026-01-01,=1+1,Food,1\n"));
        assertTrue(preview.canImport());
        assertEquals("=1+1", preview.validRows().getFirst().description());
    }

    @Test void readReturnsRowsOrThrowsFirstError() {
        assertEquals(1, a.read(utf8(HEADER_A + "2026-01-01,x,y,1\n")).size());
        ApiException error = assertThrows(ApiException.class, () -> a.read(utf8(HEADER_A + "2026-01-01,x,y,abc\n")));
        assertEquals("INVALID_AMOUNT", error.code());
    }

    private static String rows(int count) {
        StringBuilder sb = new StringBuilder(HEADER_A);
        for (int i = 0; i < count; i++) sb.append("2026-01-01,x,y,1\n");
        return sb.toString();
    }

    private static void assertFileError(CsvPreview preview) {
        assertFalse(preview.canImport());
        assertEquals(1, preview.errors().size());
        assertEquals("INVALID_CSV", preview.errors().getFirst().code());
    }
}
