package vn.edu.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import vn.edu.wallet.api.ApiDtos;
import vn.edu.wallet.expense.ByCategoryStrategy;
import vn.edu.wallet.expense.ByMonthStrategy;
import vn.edu.wallet.expense.ExpenseStatisticsService;
import vn.edu.wallet.expense.ImportedExpense;
import vn.edu.wallet.report.StatementService;

/** Manual benchmark on a disposable database: mvn -Dtest=ReportPerformanceIT test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReportPerformanceIT {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID OWNER_WALLET = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID OTHER_WALLET = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static PostgreSQLContainer container;
    @Autowired JdbcTemplate db;
    @Autowired ExpenseStatisticsService statistics;
    @Autowired ByCategoryStrategy byCategory;
    @Autowired ByMonthStrategy byMonth;
    @Autowired StatementService statements;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        container = new PostgreSQLContainer("postgres:17-alpine");
        container.start();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    @Test
    void measureLargeStatsAndStatements() throws Exception {
        seed();
        assertEquals(80_000, db.queryForObject("SELECT count(*) FROM imported_expenses", Integer.class));
        assertEquals(8_000, db.queryForObject("SELECT count(*) FROM transfers", Integer.class));
        var category = timed(() -> statistics.stats(OWNER, "2026-01-01", "2026-12-31", "category"));
        var month = timed(() -> statistics.stats(OWNER, "2026-01-01", "2026-12-31", "month"));
        var csv = timed(() -> statements.export(OWNER, "2026-01-01", "2026-12-31", "csv"));
        var pdf = timed(() -> statements.export(OWNER, "2026-01-01", "2026-12-31", "pdf"));
        var csvPage = timed(() -> statements.export(OWNER, "2026-01-01", "2026-12-31", "csv", 0, 500));
        var pdfPage = timed(() -> statements.export(OWNER, "2026-01-01", "2026-12-31", "pdf", 0, 500));
        assertEquals(80_000, category.value().totalCount());
        assertEquals(80_000, month.value().totalCount());
        List<ImportedExpense> legacyRows = db.query("""
                SELECT e.source_row,e.spent_on,e.description,e.category,e.amount_dong
                FROM imported_expenses e JOIN import_batches b ON b.id=e.batch_id
                WHERE b.owner_user_id=? AND e.spent_on BETWEEN ? AND ?
                ORDER BY e.spent_on,e.id LIMIT 100001
                """, (rs, row) -> new ImportedExpense(rs.getInt("source_row"),
                rs.getDate("spent_on").toLocalDate(), rs.getString("description"),
                rs.getString("category"), rs.getLong("amount_dong")),
                OWNER, Date.valueOf("2026-01-01"), Date.valueOf("2026-12-31"));
        assertEquals(statistics.summarize(byCategory, legacyRows).stream().map(s ->
                new ApiDtos.ExpenseSummaryView(s.key(), s.amountDong(), s.count())).toList(), category.value().items());
        assertEquals(statistics.summarize(byMonth, legacyRows).stream().map(s ->
                new ApiDtos.ExpenseSummaryView(s.key(), s.amountDong(), s.count())).toList(), month.value().items());
        assertEquals(8_001, new String(csv.value().bytes(), StandardCharsets.UTF_8).lines().count());
        assertEquals(501, new String(csvPage.value().bytes(), StandardCharsets.UTF_8).lines().count());
        assertEquals(true, csvPage.value().hasMore());
        assertEquals(true, pdfPage.value().hasMore());
        String pdfText;
        try (var document = Loader.loadPDF(pdf.value().bytes())) {
            pdfText = new PDFTextStripper().getText(document);
        }
        Properties result = new Properties();
        result.setProperty("rows.expenses", "80000");
        result.setProperty("rows.transfers", "8000");
        result.setProperty("stats.category.ms", Long.toString(category.millis()));
        result.setProperty("stats.month.ms", Long.toString(month.millis()));
        result.setProperty("statement.csv.ms", Long.toString(csv.millis()));
        result.setProperty("statement.pdf.ms", Long.toString(pdf.millis()));
        result.setProperty("statement.csv.page500.ms", Long.toString(csvPage.millis()));
        result.setProperty("statement.pdf.page500.ms", Long.toString(pdfPage.millis()));
        result.setProperty("stats.category.sha256", digest(category.value().toString().getBytes(StandardCharsets.UTF_8)));
        result.setProperty("stats.month.sha256", digest(month.value().toString().getBytes(StandardCharsets.UTF_8)));
        result.setProperty("statement.csv.sha256", digest(csv.value().bytes()));
        result.setProperty("statement.pdf.text.sha256", digest(pdfText.getBytes(StandardCharsets.UTF_8)));
        Path output = Path.of("target", "report-benchmark-" + System.getProperty("benchmark.label", "run") + ".properties");
        Files.createDirectories(output.getParent());
        try (var stream = Files.newOutputStream(output)) { result.store(stream, "Disposable PostgreSQL benchmark"); }
        System.out.println("REPORT_BENCHMARK " + output + " category=" + category.millis()
                + "ms month=" + month.millis() + "ms csv=" + csv.millis() + "ms pdf=" + pdf.millis()
                + "ms csvPage500=" + csvPage.millis() + "ms pdfPage500=" + pdfPage.millis() + "ms");
    }

    private void seed() {
        db.update("INSERT INTO app_users(id,email,display_name,password_hash,role) VALUES (?, 'bench-owner@example.test', 'Bench Owner', 'unused', 'USER')", OWNER);
        db.update("INSERT INTO app_users(id,email,display_name,password_hash,role) VALUES (?, 'bench-other@example.test', 'Bench Other', 'unused', 'USER')", OTHER);
        db.update("INSERT INTO wallets(id,owner_id,wallet_code) VALUES (?,?,'WLTBENCHOWNER')", OWNER_WALLET, OWNER);
        db.update("INSERT INTO wallets(id,owner_id,wallet_code) VALUES (?,?,'WLTBENCHOTHER')", OTHER_WALLET, OTHER);
        db.update("""
                INSERT INTO import_batches(id,owner_user_id,format_code,source_name,row_count,request_key,content_sha256)
                SELECT md5('bench-batch-' || n)::uuid, ?, 'SAMPLE_A', 'bench-' || n || '.csv', 4000,
                       md5('bench-key-' || n)::uuid, lpad(n::text,64,'0')
                FROM generate_series(1,20) n
                """, OWNER);
        db.update("""
                INSERT INTO imported_expenses(id,batch_id,source_row,spent_on,description,category,amount_dong)
                SELECT md5('bench-expense-' || b.source_name || '-' || r)::uuid, b.id, r,
                       date '2026-01-01' + ((r + length(b.source_name)) % 365),
                       'Expense ' || r, 'Category ' || (r % 40), (r % 10000) + 1
                FROM import_batches b CROSS JOIN generate_series(1,4000) r
                WHERE b.owner_user_id=?
                """, OWNER);
        db.update("""
                INSERT INTO transfers(id,sender_wallet_id,recipient_wallet_id,request_key,amount_dong,
                                      sender_balance_after_dong,recipient_balance_after_dong,created_at)
                SELECT md5('bench-transfer-' || n)::uuid,
                       CASE WHEN n % 2 = 0 THEN ?::uuid ELSE ?::uuid END,
                       CASE WHEN n % 2 = 0 THEN ?::uuid ELSE ?::uuid END,
                       md5('bench-transfer-key-' || n)::uuid, n, 1000000, 1000000,
                       timestamptz '2026-01-01 00:00:00+00' + n * interval '1 minute'
                FROM generate_series(1,8000) n
                """, OWNER_WALLET, OTHER_WALLET, OTHER_WALLET, OWNER_WALLET);
        db.execute("ANALYZE imported_expenses");
        db.execute("ANALYZE import_batches");
        db.execute("ANALYZE transfers");
    }

    private static <T> Timed<T> timed(CheckedSupplier<T> action) throws Exception {
        long start = System.nanoTime();
        T value = action.get();
        return new Timed<>(value, (System.nanoTime() - start) / 1_000_000);
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private interface CheckedSupplier<T> { T get() throws Exception; }
    private record Timed<T>(T value, long millis) {}
}
