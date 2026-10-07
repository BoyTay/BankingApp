package vn.edu.wallet;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.edu.wallet.service.TransferWriteHook;
import vn.edu.wallet.auth.AuthService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WalletApiIT.FaultConfig.class)
class WalletApiIT {
    private static final AtomicBoolean FAIL_AFTER_DEBIT = new AtomicBoolean();
    private static PostgreSQLContainer container;
    private final HttpClient http = HttpClient.newHttpClient();
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String external = System.getProperty("it.db.url");
        if (external != null && !external.isBlank()) {
            registry.add("spring.datasource.url", () -> external);
            registry.add("spring.datasource.username", () -> System.getProperty("it.db.user", "wallet_test"));
            registry.add("spring.datasource.password", () -> System.getProperty("it.db.password", ""));
        } else {
            container = new PostgreSQLContainer("postgres:17-alpine");
            container.start();
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
    }

    @AfterAll
    static void stopContainer() {
        if (container != null) container.stop();
    }

    @BeforeEach
    void reset() {
        FAIL_AFTER_DEBIT.set(false);
        db.execute("TRUNCATE TABLE ledger_entries,transfers,admin_grants,auth_sessions,imported_expenses,import_batches,wallets,app_users CASCADE");
    }

    @Test
    void registrationLoginLogoutAndHashedPassword() throws Exception {
        Account a = register("one");
        assertEquals(0, wallet(a).path("balanceDong").longValue());
        JsonNode lookup = body(request("GET", "/wallets/lookup/" + a.walletCode(), a.token(), null));
        assertEquals(a.walletCode(), lookup.path("walletCode").asString());
        assertEquals("one", lookup.path("displayName").asString());
        assertFalse(lookup.has("balanceDong"));
        assertFalse(lookup.has("email"));
        String hash = db.queryForObject("SELECT password_hash FROM app_users WHERE id=?", String.class, a.userId());
        assertTrue(hash.startsWith("$2"));
        assertFalse(hash.contains(a.password()));
        assertEquals("USER", a.role());
        assertEquals(204, request("POST", "/auth/logout", a.token(), null).statusCode());
        assertEquals(401, request("GET", "/me/wallet", a.token(), null).statusCode());
    }

    @Test
    void successfulTransferPreservesTotalAndWritesTwoLedgerRows() throws Exception {
        Account a = register("sender");
        Account b = register("receiver");
        Account admin = admin();
        grant(admin, a, 1000);
        assertEquals(admin.userId(), db.queryForObject("SELECT admin_user_id FROM admin_grants", UUID.class));
        assertEquals("Test funding", db.queryForObject("SELECT reason FROM admin_grants", String.class));
        long totalBefore = totalBalances();
        HttpResponse<String> sent = transfer(a, b, 275, UUID.randomUUID());
        assertEquals(201, sent.statusCode(), sent.body());
        JsonNode receipt = body(sent);
        assertEquals("OUTGOING", receipt.path("direction").asString());
        assertEquals(725, receipt.path("myBalanceAfterDong").longValue());
        assertPrivateTransferFields(receipt);
        assertEquals(725, wallet(a).path("balanceDong").longValue());
        assertEquals(275, wallet(b).path("balanceDong").longValue());
        assertEquals(totalBefore, totalBalances());
        assertEquals(3, count("ledger_entries")); // one grant and two transfer sides
        assertEquals(0, db.queryForObject("SELECT sum(delta_dong) FROM ledger_entries WHERE transfer_id=?",
                Long.class, UUID.fromString(receipt.path("transferId").asString())));
        JsonNode senderHistory = body(request("GET", "/transfers", a.token(), null));
        JsonNode recipientHistory = body(request("GET", "/transfers", b.token(), null));
        assertEquals(1, senderHistory.path("totalItems").longValue());
        assertEquals(1, recipientHistory.path("totalItems").longValue());
        assertEquals(725, senderHistory.path("items").get(0).path("myBalanceAfterDong").longValue());
        assertEquals(275, recipientHistory.path("items").get(0).path("myBalanceAfterDong").longValue());
        assertEquals("INCOMING", recipientHistory.path("items").get(0).path("direction").asString());
        assertPrivateTransferFields(senderHistory.path("items").get(0));
        assertPrivateTransferFields(recipientHistory.path("items").get(0));
    }

    @Test
    void rejectedTransfersLeaveBalancesAndLedgerUntouched() throws Exception {
        Account a = register("poor");
        Account b = register("other");
        UUID key = UUID.randomUUID();
        assertError(422, "INSUFFICIENT_FUNDS", transfer(a, b, 1, key));
        assertError(400, "SELF_TRANSFER", transfer(a, a, 1, UUID.randomUUID()));
        assertError(400, "INVALID_AMOUNT", transfer(a, b, 0, UUID.randomUUID()));
        assertError(400, "INVALID_AMOUNT", transferRaw(a, b.walletCode(), "1.5", UUID.randomUUID()));
        assertError(400, "INVALID_AMOUNT", transfer(a, b, 1_000_000_000_001L, UUID.randomUUID()));
        assertEquals(0, totalBalances());
        assertEquals(0, count("transfers"));
        assertEquals(0, count("ledger_entries"));
    }

    @Test
    void sameKeyReplaysExactReceiptAndChangedPayloadConflicts() throws Exception {
        Account a = register("repeat-a");
        Account b = register("repeat-b");
        Account c = register("repeat-c");
        grant(admin(), a, 100);
        UUID key = UUID.randomUUID();
        HttpResponse<String> first = transfer(a, b, 30, key);
        HttpResponse<String> replay = transfer(a, b, 30, key);
        assertEquals(201, first.statusCode());
        assertEquals(200, replay.statusCode());
        assertEquals(first.body(), replay.body());
        assertError(409, "IDEMPOTENCY_CONFLICT", transfer(a, b, 31, key));
        assertError(409, "IDEMPOTENCY_CONFLICT", transfer(a, c, 30, key));
        assertError(409, "IDEMPOTENCY_CONFLICT", transferRaw(a, b.walletCode(), "0", key));
        assertEquals(1, count("transfers"));
        assertEquals(3, count("ledger_entries")); // one grant and two transfer sides
    }

    @Test
    void recipientBalanceOverflowRejectsTransferWithoutWrites() throws Exception {
        Account a = register("overflow-a");
        Account b = register("overflow-b");
        grant(admin(), a, 10);
        db.update("UPDATE wallets SET balance_dong=? WHERE wallet_code=?", Long.MAX_VALUE, b.walletCode());
        assertError(422, "BALANCE_OVERFLOW", transfer(a, b, 1, UUID.randomUUID()));
        assertEquals(10, wallet(a).path("balanceDong").longValue());
        assertEquals(Long.MAX_VALUE, wallet(b).path("balanceDong").longValue());
        assertEquals(0, count("transfers"));
        assertEquals(1, count("ledger_entries"));
    }

    @Test
    void concurrentSameKeyCreatesExactlyOneTransfer() throws Exception {
        Account a = register("parallel-a");
        Account b = register("parallel-b");
        grant(admin(), a, 100);
        UUID key = UUID.randomUUID();
        List<HttpResponse<String>> responses = parallel(
                () -> transfer(a, b, 30, key), () -> transfer(a, b, 30, key));
        assertEquals(1, responses.stream().filter(r -> r.statusCode() == 201).count());
        assertEquals(1, responses.stream().filter(r -> r.statusCode() == 200).count());
        assertEquals(body(responses.get(0)).path("transferId").asString(),
                body(responses.get(1)).path("transferId").asString());
        assertEquals(1, count("transfers"));
        assertEquals(70, wallet(a).path("balanceDong").longValue());
    }

    @Test
    void competingTransfersCannotOverspend() throws Exception {
        Account a = register("competition-a");
        Account b = register("competition-b");
        Account c = register("competition-c");
        grant(admin(), a, 100);
        List<HttpResponse<String>> responses = parallel(
                () -> transfer(a, b, 80, UUID.randomUUID()),
                () -> transfer(a, c, 80, UUID.randomUUID()));
        assertEquals(1, responses.stream().filter(r -> r.statusCode() == 201).count());
        assertEquals(1, responses.stream().filter(r -> r.statusCode() == 422).count());
        assertEquals(20, wallet(a).path("balanceDong").longValue());
        assertEquals(1, count("transfers"));
        assertEquals(100, totalBalances());
    }

    @Test
    void reverseDirectionTransfersCompleteWithoutDeadlock() throws Exception {
        Account a = register("reverse-a");
        Account b = register("reverse-b");
        Account admin = admin();
        grant(admin, a, 100);
        grant(admin, b, 100);
        List<HttpResponse<String>> responses = parallel(
                () -> transfer(a, b, 40, UUID.randomUUID()),
                () -> transfer(b, a, 40, UUID.randomUUID()));
        assertTrue(responses.stream().allMatch(r -> r.statusCode() == 201), responses.toString());
        assertEquals(100, wallet(a).path("balanceDong").longValue());
        assertEquals(100, wallet(b).path("balanceDong").longValue());
        assertEquals(2, count("transfers"));
    }

    @Test
    void injectedFailureAfterDebitRollsBackEverything() throws Exception {
        Account a = register("rollback-a");
        Account b = register("rollback-b");
        grant(admin(), a, 100);
        FAIL_AFTER_DEBIT.set(true);
        assertEquals(500, transfer(a, b, 20, UUID.randomUUID()).statusCode());
        FAIL_AFTER_DEBIT.set(false);
        assertEquals(100, wallet(a).path("balanceDong").longValue());
        assertEquals(0, wallet(b).path("balanceDong").longValue());
        assertEquals(0, count("transfers"));
        assertEquals(1, count("ledger_entries")); // grant only
    }

    @Test
    void userCannotGrantOrReadOtherPeoplesReceipt() throws Exception {
        Account a = register("private-a");
        Account b = register("private-b");
        Account outsider = register("private-c");
        Account admin = admin();
        assertError(403, "FORBIDDEN", request("POST", "/admin/grants", a.token(),
                "{\"recipientWalletCode\":\"" + a.walletCode() + "\",\"amountDong\":100,\"reason\":\"test\"}"));
        grant(admin, a, 100);
        String id = body(transfer(a, b, 20, UUID.randomUUID())).path("transferId").asString();
        HttpResponse<String> authorizedReceipt = request("GET", "/transfers/" + id, b.token(), null);
        assertEquals(200, authorizedReceipt.statusCode(), authorizedReceipt.body());
        assertEquals("INCOMING", body(authorizedReceipt).path("direction").asString());
        assertEquals(20, body(authorizedReceipt).path("myBalanceAfterDong").longValue());
        assertPrivateTransferFields(body(authorizedReceipt));
        assertError(404, "TRANSFER_NOT_FOUND", request("GET", "/transfers/" + id, outsider.token(), null));
        assertError(404, "TRANSFER_NOT_FOUND", request("GET", "/transfers/" + id, admin.token(), null));
        assertEquals(0, body(request("GET", "/transfers", outsider.token(), null)).path("totalItems").longValue());
    }

    @Test
    void statementsExportOwnTransactionsInCsvAndPdf() throws Exception {
        Account a = register("statement-a");
        Account b = register("statement-b");
        Account c = register("statement-c");
        Account admin = admin();
        grant(admin, a, 1000);
        grant(admin, b, 500);
        String ownId = body(transfer(a, b, 275, UUID.randomUUID())).path("transferId").asString();
        String foreignId = body(transfer(b, c, 100, UUID.randomUUID())).path("transferId").asString();
        String query = "/statements?from=2026-01-01&to=2026-12-31&format=";
        HttpResponse<byte[]> csv = download(a, query + "csv");
        assertEquals(200, csv.statusCode());
        assertTrue(csv.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));
        assertTrue(csv.headers().firstValue("Content-Disposition").orElse("").contains("statement-20260101-20261231.csv"));
        String csvText = new String(csv.body(), StandardCharsets.UTF_8);
        assertTrue(csvText.startsWith("\uFEFFThời gian UTC"));
        assertTrue(csvText.contains(ownId));
        assertTrue(csvText.contains("Chuyển đi"));
        assertTrue(csvText.contains(",275,725"));
        assertFalse(csvText.contains(foreignId));
        assertFalse(csvText.contains(",600")); // recipient's balance after first transfer

        HttpResponse<byte[]> pdf = download(a, query + "pdf");
        assertEquals(200, pdf.statusCode());
        assertEquals("application/pdf", pdf.headers().firstValue("Content-Type").orElse(""));
        assertTrue(new String(pdf.body(), 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"));
        Files.createDirectories(Path.of("target", "test-artifacts"));
        Files.write(Path.of("target", "test-artifacts", "statement-sample.pdf"), pdf.body());
        try (var document = Loader.loadPDF(pdf.body())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("SAO KÊ CHUYỂN TIỀN"), text);
            assertTrue(text.contains(a.walletCode()), text);
            assertTrue(text.contains("Tổng chuyển đi: 275 VND"), text);
            assertFalse(text.contains(foreignId), text);
        }
        assertError(400, "INVALID_DATE_RANGE", request("GET",
                "/statements?from=2026-01-01&to=2027-12-31&format=csv", a.token(), null));
        assertEquals(401, download(c, query + "csv", false).statusCode());
    }

    @Test
    void importBothFormatsAggregatesOnlyOwnerAndNeverChangesWalletOrLedger() throws Exception {
        Account a = register("expense-a");
        Account b = register("expense-b");
        grant(admin(), a, 1000);
        long balanceBefore = wallet(a).path("balanceDong").longValue();
        int ledgerBefore = count("ledger_entries");
        byte[] sampleA = Files.readAllBytes(Path.of("samples/expenses-a.csv"));
        byte[] sampleB = Files.readAllBytes(Path.of("samples/expenses-b.csv"));
        UUID key = UUID.randomUUID();
        HttpResponse<String> first = upload(a, "SAMPLE_A", key, sampleA);
        assertEquals(201, first.statusCode(), first.body());
        assertEquals(2, body(first).path("rowCount").intValue());
        assertEquals(200, upload(a, "SAMPLE_A", key, sampleA).statusCode());
        assertEquals(200, upload(a, "SAMPLE_A", UUID.randomUUID(), sampleA).statusCode());
        assertError(409, "IMPORT_KEY_CONFLICT", upload(a, "SAMPLE_B", key, sampleB));
        assertEquals(201, upload(a, "SAMPLE_B", UUID.randomUUID(), sampleB).statusCode());
        assertEquals(2, count("import_batches"));
        assertEquals(4, count("imported_expenses"));
        assertEquals(balanceBefore, wallet(a).path("balanceDong").longValue());
        assertEquals(ledgerBefore, count("ledger_entries"));

        JsonNode category = body(request("GET", "/expense-stats?from=2026-01-01&to=2026-12-31&groupBy=category", a.token(), null));
        assertEquals(100000, category.path("totalAmountDong").longValue());
        assertEquals(4, category.path("totalCount").longValue());
        assertEquals(4, category.path("items").size());
        JsonNode month = body(request("GET", "/expense-stats?from=2026-01-01&to=2026-12-31&groupBy=month", a.token(), null));
        assertEquals("2026-01", month.path("items").get(0).path("key").asString());
        assertEquals(57000, month.path("items").get(0).path("amountDong").longValue());
        assertEquals("2026-02", month.path("items").get(1).path("key").asString());
        assertEquals(43000, month.path("items").get(1).path("amountDong").longValue());
        JsonNode other = body(request("GET", "/expense-stats?from=2026-01-01&to=2026-12-31&groupBy=category", b.token(), null));
        assertEquals(0, other.path("totalAmountDong").longValue());
        assertEquals(0, other.path("items").size());
    }

    @Test
    void malformedCsvAndAmountsAreRejectedWithoutPartialImport() throws Exception {
        Account a = register("bad-csv");
        String header = "date,description,category,amount_vnd\n";
        assertError(400, "INVALID_CSV", upload(a, "SAMPLE_A", UUID.randomUUID(),
                "bad,header\n2026-01-01,x,y,1\n".getBytes(StandardCharsets.UTF_8)));
        assertError(400, "INVALID_CSV", upload(a, "SAMPLE_A", UUID.randomUUID(),
                (header + "2026-01-01,good,food,1\ninvalid,bad,food,2\n").getBytes(StandardCharsets.UTF_8)));
        assertError(400, "INVALID_AMOUNT", upload(a, "SAMPLE_A", UUID.randomUUID(),
                (header + "2026-01-01,bad,food,-1\n").getBytes(StandardCharsets.UTF_8)));
        assertError(400, "INVALID_AMOUNT", upload(a, "SAMPLE_A", UUID.randomUUID(),
                (header + "2026-01-01,bad,food,1000000000001\n").getBytes(StandardCharsets.UTF_8)));
        assertError(400, "INVALID_CSV", upload(a, "SAMPLE_A", UUID.randomUUID(), new byte[] {(byte) 0xC3, 0x28}));
        assertError(413, "FILE_TOO_LARGE", upload(a, "SAMPLE_A", UUID.randomUUID(), new byte[1_048_577]));
        assertEquals(0, count("import_batches"));
        assertEquals(0, count("imported_expenses"));
        assertEquals(0, count("ledger_entries"));
        assertEquals(0, wallet(a).path("balanceDong").longValue());
    }

    @Test
    void sameImportKeyConcurrentCreatesOneBatch() throws Exception {
        Account a = register("import-race");
        byte[] bytes = Files.readAllBytes(Path.of("samples/expenses-b.csv"));
        UUID key = UUID.randomUUID();
        List<HttpResponse<String>> responses = parallel(
                () -> upload(a, "SAMPLE_B", key, bytes),
                () -> upload(a, "SAMPLE_B", key, bytes));
        assertEquals(1, responses.stream().filter(r -> r.statusCode() == 201).count());
        assertEquals(1, responses.stream().filter(r -> r.statusCode() == 200).count());
        assertEquals(1, count("import_batches"));
        assertEquals(2, count("imported_expenses"));
    }

    private HttpResponse<byte[]> download(Account account, String path) throws Exception {
        return download(account, path, true);
    }

    private HttpResponse<byte[]> download(Account account, String path, boolean authenticated) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path));
        if (authenticated) builder.header("Authorization", "Bearer " + account.token());
        return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> upload(Account account, String format, UUID key, byte[] file) throws Exception {
        String boundary = "test-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"requestKey\"\r\n\r\n"
                + key + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"expenses.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(file);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/expense-imports?format=" + format))
                .header("Authorization", "Bearer " + account.token())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private Account register(String label) throws Exception {
        String email = label + "-" + UUID.randomUUID() + "@example.test";
        String password = "Test-" + UUID.randomUUID();
        HttpResponse<String> created = request("POST", "/auth/register", null,
                "{\"email\":\"" + email + "\",\"displayName\":\"" + label + "\",\"password\":\"" + password + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        JsonNode data = body(created);
        HttpResponse<String> login = request("POST", "/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, login.statusCode(), login.body());
        return new Account(UUID.fromString(data.path("userId").asString()),
                data.path("wallet").path("walletCode").asString(), body(login).path("accessToken").asString(),
                password, data.path("role").asString());
    }

    private Account admin() throws Exception {
        String email = "admin-" + UUID.randomUUID() + "@example.test";
        String password = "Test-" + UUID.randomUUID();
        auth.bootstrapAdmin(email, password);
        HttpResponse<String> login = request("POST", "/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, login.statusCode(), login.body());
        UUID id = UUID.fromString(body(login).path("user").path("userId").asString());
        String code = db.queryForObject("SELECT wallet_code FROM wallets WHERE owner_id=?", String.class, id);
        return new Account(id, code, body(login).path("accessToken").asString(), password, "ADMIN");
    }

    private void grant(Account admin, Account to, long amount) throws Exception {
        HttpResponse<String> response = request("POST", "/admin/grants", admin.token(),
                "{\"recipientWalletCode\":\"" + to.walletCode() + "\",\"amountDong\":" + amount + ",\"reason\":\"Test funding\"}");
        assertEquals(201, response.statusCode(), response.body());
    }

    private HttpResponse<String> transfer(Account from, Account to, long amount, UUID key) throws Exception {
        return transferRaw(from, to.walletCode(), Long.toString(amount), key);
    }

    private HttpResponse<String> transferRaw(Account from, String recipient, String amountJson, UUID key) throws Exception {
        return request("POST", "/transfers", from.token(),
                "{\"requestKey\":\"" + key + "\",\"recipientWalletCode\":\"" + recipient
                        + "\",\"amountDong\":" + amountJson + "}");
    }

    private JsonNode wallet(Account account) throws Exception {
        HttpResponse<String> response = request("GET", "/me/wallet", account.token(), null);
        assertEquals(200, response.statusCode(), response.body());
        return body(response);
    }

    private long totalBalances() { return db.queryForObject("SELECT coalesce(sum(balance_dong),0) FROM wallets", Long.class); }
    private int count(String table) { return db.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }

    private void assertPrivateTransferFields(JsonNode transfer) {
        assertFalse(transfer.has("senderBalanceAfterDong"));
        assertFalse(transfer.has("recipientBalanceAfterDong"));
    }

    private void assertError(int status, String code, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, body(response).path("code").asString());
    }

    private HttpResponse<String> request(String method, String path, String token, String payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Accept", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (payload != null) builder.header("Content-Type", "application/json; charset=UTF-8");
        builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private List<HttpResponse<String>> parallel(CheckedRequest first, CheckedRequest second) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { go.await(); return first.send(); });
            var b = executor.submit(() -> { go.await(); return second.send(); });
            go.countDown();
            List<HttpResponse<String>> results = new ArrayList<>();
            results.add(a.get(20, TimeUnit.SECONDS));
            results.add(b.get(20, TimeUnit.SECONDS));
            return results;
        }
    }

    @FunctionalInterface private interface CheckedRequest { HttpResponse<String> send() throws Exception; }
    private record Account(UUID userId, String walletCode, String token, String password, String role) {}

    @TestConfiguration
    static class FaultConfig {
        @Bean @Primary TransferWriteHook faultHook() {
            return () -> { if (FAIL_AFTER_DEBIT.get()) throw new IllegalStateException("injected after debit"); };
        }
    }
}
