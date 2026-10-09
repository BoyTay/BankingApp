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
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import vn.edu.wallet.auth.AuthMaintenance;
import vn.edu.wallet.service.AccountFeeService;
import vn.edu.wallet.service.AccountService;
import vn.edu.wallet.service.WalletQueries;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WalletApiIT.FaultConfig.class)
class WalletApiIT {
    private static final AtomicBoolean FAIL_AFTER_DEBIT = new AtomicBoolean();
    private static PostgreSQLContainer container;
    private final HttpClient http = HttpClient.newHttpClient();
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;
    @Autowired AuthMaintenance authMaintenance;
    @Autowired AccountFeeService accountFees;
    @Autowired AccountService accountService;
    @Autowired WalletQueries walletQueries;
    @Autowired vn.edu.wallet.notify.LowBalanceMonitor lowBalanceMonitor;
    @Autowired vn.edu.wallet.notify.ReminderService reminders;
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
        db.execute("TRUNCATE TABLE ledger_entries,transfers,admin_grants,auth_sessions,auth_rate_limits,imported_expenses,import_batches,wallets,app_users CASCADE");
    }

    @Test
    void multipleCheckingAccountsKeepOwnershipAndLegacyDefault() throws Exception {
        Account owner = register("multi-owner");
        Account other = register("multi-other");
        Account administrator = admin();
        UUID key = UUID.randomUUID();
        String open = "{\"requestKey\":\"" + key + "\",\"type\":\"CHECKING\"}";
        HttpResponse<String> created = request("POST", "/me/accounts", owner.token(), open);
        assertEquals(201, created.statusCode(), created.body());
        JsonNode secondary = body(created);
        UUID secondaryId = UUID.fromString(secondary.path("accountId").asString());
        String secondaryCode = secondary.path("accountCode").asString();
        assertFalse(secondary.path("isDefault").asBoolean());
        assertEquals(created.body(), request("POST", "/me/accounts", owner.token(), open).body());
        assertEquals(2, body(request("GET", "/me/accounts", owner.token(), null)).size());
        assertEquals(owner.walletCode(), wallet(owner).path("walletCode").asString());
        assertError(404, "ACCOUNT_NOT_FOUND", request("GET", "/me/accounts/" + secondaryId,
                other.token(), null));
        assertError(400, "ACCOUNT_TYPE_UNAVAILABLE", request("POST", "/me/accounts", owner.token(),
                "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"type\":\"UNKNOWN\"}"));

        assertEquals(201, grantRaw(administrator, secondaryCode, "100", "fund secondary",
                UUID.randomUUID()).statusCode());
        UUID transferKey = UUID.randomUUID();
        String transferBody = "{\"requestKey\":\"" + transferKey + "\",\"recipientWalletCode\":\""
                + other.walletCode() + "\",\"amountDong\":40,\"sourceAccountId\":\"" + secondaryId + "\"}";
        HttpResponse<String> sent = request("POST", "/transfers", owner.token(), transferBody);
        assertEquals(201, sent.statusCode(), sent.body());
        assertEquals(200, request("POST", "/transfers", owner.token(), transferBody).statusCode());
        String transferId = body(sent).path("transferId").asString();
        assertEquals(200, request("GET", "/transfers/" + transferId, owner.token(), null).statusCode());
        assertError(404, "TRANSFER_NOT_FOUND", request("GET", "/transfers/" + transferId,
                administrator.token(), null));
        assertEquals(0, body(request("GET", "/transfers", owner.token(), null)).path("totalItems").longValue());
        assertEquals(1, body(request("GET", "/transfers?accountId=" + secondaryId,
                owner.token(), null)).path("totalItems").longValue());
        assertError(404, "WALLET_NOT_FOUND", request("GET", "/transfers?accountId=" + secondaryId,
                other.token(), null));
        String statementPath = "/statements?from=2026-01-01&to=2026-12-31&format=csv&accountId=" + secondaryId;
        assertTrue(new String(download(owner, statementPath).body(), StandardCharsets.UTF_8).contains(transferId));
        assertEquals(404, download(other, statementPath).statusCode());
        assertEquals(60, body(request("GET", "/me/accounts/" + secondaryId,
                owner.token(), null)).path("balanceDong").longValue());
    }

    @Test
    void checkingFeeIsDueUntilFundedAndPaidOnlyOnce() throws Exception {
        Account owner = register("fee-owner");
        Account administrator = admin();
        UUID key = UUID.randomUUID();
        JsonNode account = body(request("POST", "/me/accounts", owner.token(),
                "{\"requestKey\":\"" + key + "\",\"type\":\"CHECKING\"}"));
        UUID id = UUID.fromString(account.path("accountId").asString());
        String code = account.path("accountCode").asString();
        LocalDate startsOn = db.queryForObject("SELECT fee_starts_on FROM wallets WHERE id=?",
                (rs, row) -> rs.getDate(1).toLocalDate(), id);
        assertEquals(LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).plusMonths(1), startsOn);
        assertEquals(0, accountFees.assessAndCollect(startsOn.minusDays(1)).assessed());
        assertTrue(accountFees.assessAndCollect(startsOn).assessed() >= 1);
        JsonNode due = body(request("GET", "/me/accounts/" + id + "/fees", owner.token(), null));
        assertEquals(1, due.size());
        assertEquals("DUE", due.get(0).path("status").asString());
        assertEquals(5000, due.get(0).path("amountDong").longValue());
        assertError(404, "ACCOUNT_NOT_FOUND", request("GET", "/me/accounts/" + id + "/fees",
                administrator.token(), null));
        assertEquals(201, grantRaw(administrator, code, "6000", "fee funding",
                UUID.randomUUID()).statusCode());
        assertEquals(1, accountFees.assessAndCollect(startsOn).paid());
        assertEquals(0, accountFees.assessAndCollect(startsOn).paid());
        assertEquals(1000, body(request("GET", "/me/accounts/" + id,
                owner.token(), null)).path("balanceDong").longValue());
        assertEquals("PAID", body(request("GET", "/me/accounts/" + id + "/fees",
                owner.token(), null)).get(0).path("status").asString());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM ledger_entries WHERE fee_id IS NOT NULL",
                Integer.class));
        assertEquals(0, body(request("GET", "/admin/reconciliation", administrator.token(), null))
                .path("mismatchCount").longValue());
    }

    @Test
    void creditLimitSpendRepayFeeAndCloseKeepLedgerConsistent() throws Exception {
        Account owner = register("credit-owner");
        Account other = register("credit-other");
        Account administrator = admin();
        JsonNode opened = body(request("POST", "/me/accounts", owner.token(),
                "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"type\":\"CREDIT\"}"));
        UUID id = UUID.fromString(opened.path("accountId").asString());
        assertEquals(0, body(request("GET", "/me/accounts/" + id + "/credit", owner.token(), null))
                .path("limitDong").longValue());
        assertError(404, "ACCOUNT_NOT_FOUND", request("GET", "/me/accounts/" + id + "/credit",
                other.token(), null));
        String limit = "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"limitDong\":100000}";
        assertError(403, "FORBIDDEN", request("POST", "/admin/credit-accounts/" + id + "/limit",
                owner.token(), limit));
        assertEquals(201, request("POST", "/admin/credit-accounts/" + id + "/limit",
                administrator.token(), limit).statusCode());
        assertEquals(200, request("POST", "/admin/credit-accounts/" + id + "/limit",
                administrator.token(), limit).statusCode());
        String charge = "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"amountDong\":60000,\"description\":\"Minh họa\"}";
        assertEquals(201, request("POST", "/me/accounts/" + id + "/credit/charges", owner.token(), charge).statusCode());
        assertEquals(200, request("POST", "/me/accounts/" + id + "/credit/charges", owner.token(), charge).statusCode());
        assertError(422, "CREDIT_LIMIT_EXCEEDED", request("POST", "/me/accounts/" + id + "/credit/charges",
                owner.token(), "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"amountDong\":50000,\"description\":\"Quá hạn mức\"}"));
        assertError(422, "LIMIT_BELOW_DEBT", request("POST", "/admin/credit-accounts/" + id + "/limit",
                administrator.token(), "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"limitDong\":50000}"));
        grant(administrator, owner, 100000);
        String repay = "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"sourceAccountId\":\""
                + wallet(owner).path("walletId").asString() + "\",\"amountDong\":60000}";
        assertEquals(201, request("POST", "/me/accounts/" + id + "/credit/repayments", owner.token(), repay).statusCode());
        assertEquals(200, request("POST", "/me/accounts/" + id + "/credit/repayments", owner.token(), repay).statusCode());
        assertEquals(0, body(request("GET", "/me/accounts/" + id + "/credit", owner.token(), null))
                .path("debtDong").longValue());
        LocalDate startsOn = db.queryForObject("SELECT fee_starts_on FROM wallets WHERE id=?",
                (rs, row) -> rs.getDate(1).toLocalDate(), id);
        accountFees.assessAndCollect(startsOn);
        JsonNode fee = body(request("GET", "/me/accounts/" + id + "/fees", owner.token(), null)).get(0);
        assertEquals("CREDIT_ANNUAL", fee.path("feeCode").asString());
        assertEquals("PAID", fee.path("status").asString());
        assertEquals(20000, body(request("GET", "/me/accounts/" + id + "/credit", owner.token(), null))
                .path("debtDong").longValue());
        assertEquals(0, body(request("GET", "/admin/reconciliation", administrator.token(), null))
                .path("mismatchCount").longValue());
        assertError(422, "ACCOUNT_NOT_EMPTY", request("POST", "/me/accounts/" + id + "/close",
                owner.token(), "{\"requestKey\":\"" + UUID.randomUUID() + "\"}"));
        String repayFee = "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"sourceAccountId\":\""
                + wallet(owner).path("walletId").asString() + "\",\"amountDong\":20000}";
        assertEquals(201, request("POST", "/me/accounts/" + id + "/credit/repayments", owner.token(), repayFee).statusCode());
        String close = "{\"requestKey\":\"" + UUID.randomUUID() + "\"}";
        assertEquals(201, request("POST", "/me/accounts/" + id + "/close", owner.token(), close).statusCode());
        assertEquals(200, request("POST", "/me/accounts/" + id + "/close", owner.token(), close).statusCode());
        assertEquals(0, body(request("GET", "/admin/reconciliation", administrator.token(), null))
                .path("mismatchCount").longValue());
    }

    @Test
    void savingsEarlyWithdrawalChargesFeeAndReplaysSafely() throws Exception {
        Account owner = register("savings-early");
        Account other = register("savings-foreign");
        Account administrator = admin();
        grant(administrator, owner, 300_000);
        UUID openKey = UUID.randomUUID();
        UUID fundingId = UUID.fromString(wallet(owner).path("walletId").asString());
        String opening = "{\"requestKey\":\"" + openKey + "\",\"type\":\"SAVINGS\","
                + "\"fundingAccountId\":\"" + fundingId + "\",\"amountDong\":200000}";
        HttpResponse<String> created = request("POST", "/me/accounts", owner.token(), opening);
        assertEquals(201, created.statusCode(), created.body());
        JsonNode saving = body(created);
        UUID id = UUID.fromString(saving.path("accountId").asString());
        String code = saving.path("accountCode").asString();
        assertEquals("SAVINGS", saving.path("accountType").asString());
        assertEquals(200_000, saving.path("balanceDong").longValue());
        assertEquals(100_000, wallet(owner).path("balanceDong").longValue());
        assertEquals(created.body(), request("POST", "/me/accounts", owner.token(), opening).body());
        assertError(409, "ACCOUNT_KEY_CONFLICT", request("POST", "/me/accounts", owner.token(),
                opening.replace("200000", "200001")));
        assertError(404, "ACCOUNT_NOT_FOUND", request("GET", "/me/accounts/" + id + "/savings",
                other.token(), null));
        assertError(422, "ACCOUNT_OPERATION_NOT_ALLOWED", transferRaw(owner, code, "1", UUID.randomUUID()));
        assertError(422, "ACCOUNT_OPERATION_NOT_ALLOWED", grantRaw(administrator, code, "1",
                "blocked", UUID.randomUUID()));

        UUID closeKey = UUID.randomUUID();
        String withdrawal = "{\"requestKey\":\"" + closeKey + "\"}";
        HttpResponse<String> first = request("POST", "/me/accounts/" + id + "/savings/withdraw",
                owner.token(), withdrawal);
        assertEquals(201, first.statusCode(), first.body());
        JsonNode result = body(first);
        assertFalse(result.path("matured").asBoolean());
        assertEquals(1000, result.path("feeDong").longValue());
        assertEquals(0, result.path("interestDong").longValue());
        assertEquals(199_000, result.path("payoutDong").longValue());
        assertEquals(299_000, wallet(owner).path("balanceDong").longValue());
        assertEquals(0, body(request("GET", "/me/accounts/" + id, owner.token(), null))
                .path("balanceDong").longValue());
        assertEquals("CLOSED", body(request("GET", "/me/accounts/" + id, owner.token(), null))
                .path("status").asString());
        HttpResponse<String> replay = request("POST", "/me/accounts/" + id + "/savings/withdraw",
                owner.token(), withdrawal);
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(first.body(), replay.body());
        assertError(409, "ACCOUNT_CLOSED", request("POST", "/me/accounts/" + id + "/savings/withdraw",
                owner.token(), "{\"requestKey\":\"" + UUID.randomUUID() + "\"}"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM account_fees WHERE wallet_id=?", Integer.class, id));
        String path = "/statements?from=2026-01-01&to=2026-12-31&format=csv&accountId=" + id;
        HttpResponse<byte[]> statement = download(owner, path);
        assertEquals(200, statement.statusCode());
        String csv = new String(statement.body(), StandardCharsets.UTF_8);
        assertTrue(csv.contains(result.path("transferId").asString()));
        String openingTransfer = db.queryForObject("SELECT opening_transfer_id FROM savings_accounts WHERE wallet_id=?",
                (rs, row) -> rs.getObject(1, UUID.class).toString(), id);
        assertTrue(csv.contains(openingTransfer));
        assertEquals(0, body(request("GET", "/admin/reconciliation", administrator.token(), null))
                .path("mismatchCount").longValue());
    }

    @Test
    void savingsAtMaturityCreditsInterestWithoutFee() throws Exception {
        Account owner = register("savings-mature");
        Account administrator = admin();
        grant(administrator, owner, 200_000);
        UUID fundingId = UUID.fromString(wallet(owner).path("walletId").asString());
        HttpResponse<String> opened = request("POST", "/me/accounts", owner.token(),
                "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"type\":\"SAVINGS\","
                        + "\"fundingAccountId\":\"" + fundingId + "\",\"amountDong\":200000}");
        assertEquals(201, opened.statusCode(), opened.body());
        UUID id = UUID.fromString(body(opened).path("accountId").asString());
        db.update("UPDATE savings_accounts SET matures_on=? WHERE wallet_id=?",
                java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC).minusDays(1)), id);
        String withdrawal = "{\"requestKey\":\"" + UUID.randomUUID() + "\"}";
        List<HttpResponse<String>> results = parallel(
                () -> request("POST", "/me/accounts/" + id + "/savings/withdraw", owner.token(), withdrawal),
                () -> request("POST", "/me/accounts/" + id + "/savings/withdraw", owner.token(), withdrawal));
        assertEquals(1, results.stream().filter(r -> r.statusCode() == 201).count());
        assertEquals(1, results.stream().filter(r -> r.statusCode() == 200).count());
        assertEquals(results.get(0).body(), results.get(1).body());
        JsonNode result = body(results.getFirst());
        assertTrue(result.path("matured").asBoolean());
        assertEquals(1973, result.path("interestDong").longValue());
        assertEquals(0, result.path("feeDong").longValue());
        assertEquals(201_973, wallet(owner).path("balanceDong").longValue());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM savings_interest WHERE wallet_id=?",
                Integer.class, id));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM account_fees WHERE wallet_id=?",
                Integer.class, id));
        assertEquals(0, body(request("GET", "/admin/reconciliation", administrator.token(), null))
                .path("mismatchCount").longValue());
    }

    @Test
    void insufficientCheckingBalanceRollsBackSavingsOpening() throws Exception {
        Account owner = register("savings-no-funds");
        UUID fundingId = UUID.fromString(wallet(owner).path("walletId").asString());
        assertError(422, "INSUFFICIENT_FUNDS", request("POST", "/me/accounts", owner.token(),
                "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"type\":\"SAVINGS\","
                        + "\"fundingAccountId\":\"" + fundingId + "\",\"amountDong\":200000}"));
        assertEquals(1, body(request("GET", "/me/accounts", owner.token(), null)).size());
        assertEquals(0, count("savings_accounts"));
        assertEquals(0, count("transfers"));
    }

    @Test
    void unsupportedAccountPolicyAndBadFeeSettingDoNotBlockLogin() throws Exception {
        Account owner = register("policy-owner");
        Account other = register("policy-other");
        Account administrator = admin();
        JsonNode account = body(request("POST", "/me/accounts", owner.token(),
                "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"type\":\"CHECKING\"}"));
        UUID id = UUID.fromString(account.path("accountId").asString());
        String code = account.path("accountCode").asString();
        db.update("UPDATE wallets SET account_type='SAVINGS' WHERE id=?", id);
        assertError(422, "ACCOUNT_OPERATION_NOT_ALLOWED", request("POST", "/transfers", owner.token(),
                "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"sourceAccountId\":\"" + id
                        + "\",\"recipientWalletCode\":\"" + other.walletCode() + "\",\"amountDong\":1}"));
        assertError(422, "ACCOUNT_OPERATION_NOT_ALLOWED", grantRaw(administrator, code, "1",
                "unsupported", UUID.randomUUID()));
        var badConfiguration = new AccountFeeService(db, walletQueries, accountService, lowBalanceMonitor, "invalid", "invalid");
        assertFalse(badConfiguration.assessAndCollect(LocalDate.now(ZoneOffset.UTC)).ran());
        assertEquals(200, request("GET", "/me/wallet", owner.token(), null).statusCode());
    }

    @Test
    void loginThrottleExpiresAndSuccessfulLoginClearsAttempts() throws Exception {
        Account account = register("throttle-login");
        Account other = register("unaffected-login");
        String email = db.queryForObject("SELECT email FROM app_users WHERE id=?", String.class, account.userId());
        for (int i = 0; i < 10; i++) {
            assertError(401, "INVALID_CREDENTIALS", request("POST", "/auth/login", null,
                    "{\"email\":\"" + email + "\",\"password\":\"incorrect-password\"}"));
        }
        HttpResponse<String> limited = request("POST", "/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + account.password() + "\"}");
        assertError(429, "RATE_LIMITED", limited);
        int retryAfter = Integer.parseInt(limited.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retryAfter >= 1 && retryAfter <= 60);
        String otherEmail = db.queryForObject("SELECT email FROM app_users WHERE id=?", String.class, other.userId());
        assertEquals(200, request("POST", "/auth/login", null,
                "{\"email\":\"" + otherEmail + "\",\"password\":\"" + other.password() + "\"}").statusCode());

        db.update("UPDATE auth_rate_limits SET window_ends_at=now()-interval '1 second' WHERE scope='LOGIN'");
        assertEquals(200, request("POST", "/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + account.password() + "\"}").statusCode());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM auth_rate_limits WHERE scope='LOGIN'", Integer.class));
        assertError(401, "INVALID_CREDENTIALS", request("POST", "/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"incorrect-password\"}"));
        assertEquals(200, request("POST", "/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + account.password() + "\"}").statusCode());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM auth_rate_limits WHERE scope='LOGIN'", Integer.class));
    }

    @Test
    void registrationThrottleExpiresWithoutPermanentLockout() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertError(400, "INVALID_EMAIL", request("POST", "/auth/register", null,
                    "{\"email\":\"bad-email\",\"displayName\":\"Test\",\"password\":\"StrongPass123!\"}"));
        }
        HttpResponse<String> limited = request("POST", "/auth/register", null,
                "{\"email\":\"new@example.test\",\"displayName\":\"Test\",\"password\":\"StrongPass123!\"}");
        assertError(429, "RATE_LIMITED", limited);
        assertTrue(Integer.parseInt(limited.headers().firstValue("Retry-After").orElseThrow()) <= 60);
        db.update("UPDATE auth_rate_limits SET window_ends_at=now()-interval '1 second' WHERE scope='REGISTER'");
        assertEquals(201, request("POST", "/auth/register", null,
                "{\"email\":\"new@example.test\",\"displayName\":\"Test\",\"password\":\"StrongPass123!\"}").statusCode());
    }

    @Test
    void authMaintenanceDeletesExpiredSessionsButKeepsActiveOne() throws Exception {
        Account expired = register("expired-session");
        Account active = register("active-session");
        db.update("UPDATE auth_sessions SET expires_at=now()-interval '1 minute' WHERE user_id=?", expired.userId());
        db.update("UPDATE auth_rate_limits SET window_ends_at=now()-interval '1 second'");
        authMaintenance.purgeExpired();
        assertEquals(1, count("auth_sessions"));
        assertEquals(0, count("auth_rate_limits"));
        assertError(401, "UNAUTHORIZED", request("GET", "/me/wallet", expired.token(), null));
        assertEquals(200, request("GET", "/me/wallet", active.token(), null).statusCode());
        assertEquals(204, request("POST", "/auth/logout", active.token(), null).statusCode());
        authMaintenance.purgeExpired();
        assertEquals(0, count("auth_sessions"));
    }

    @Test
    void healthEndpointReportsDatabaseStatusWithoutCredentials() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("UP", json.readTree(response.body()).path("status").asString());
        assertFalse(json.readTree(response.body()).has("components"));
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
    void reconciliationStaysBalancedAfterGrantsTransfersRetriesAndRollback() throws Exception {
        Account administrator = admin();
        Account sender = register("reconcile-sender");
        Account recipient = register("reconcile-recipient");
        assertError(401, "UNAUTHORIZED", request("GET", "/admin/reconciliation", null, null));
        assertError(403, "FORBIDDEN", request("GET", "/admin/reconciliation", sender.token(), null));

        UUID grantKey = UUID.randomUUID();
        assertEquals(201, grantRaw(administrator, sender.walletCode(), "100", "Test funding", grantKey).statusCode());
        assertEquals(200, grantRaw(administrator, sender.walletCode(), "100", "Test funding", grantKey).statusCode());
        UUID transferKey = UUID.randomUUID();
        assertEquals(201, transfer(sender, recipient, 40, transferKey).statusCode());
        assertEquals(200, transfer(sender, recipient, 40, transferKey).statusCode());
        JsonNode balanced = body(request("GET", "/admin/reconciliation", administrator.token(), null));
        assertEquals(3, balanced.path("checkedWallets").longValue());
        assertEquals(0, balanced.path("mismatchCount").longValue());
        assertEquals(0, balanced.path("items").size());
        assertFalse(balanced.path("checkedAt").asString().isBlank());

        FAIL_AFTER_DEBIT.set(true);
        assertEquals(500, transfer(sender, recipient, 10, UUID.randomUUID()).statusCode());
        FAIL_AFTER_DEBIT.set(false);
        JsonNode afterRollback = body(request("GET", "/admin/reconciliation", administrator.token(), null));
        assertEquals(0, afterRollback.path("mismatchCount").longValue());
        assertEquals(60, wallet(sender).path("balanceDong").longValue());
        assertEquals(40, wallet(recipient).path("balanceDong").longValue());
        assertEquals(1, count("admin_grants"));
        assertEquals(1, count("transfers"));
        assertEquals(3, count("ledger_entries"));
    }

    @Test
    void reconciliationReportsExactDifferencesWithoutChangingWallets() throws Exception {
        Account administrator = admin();
        Account sender = register("reconcile-a");
        Account recipient = register("reconcile-b");
        grant(administrator, sender, 100);
        assertEquals(201, transfer(sender, recipient, 40, UUID.randomUUID()).statusCode());
        db.update("UPDATE wallets SET balance_dong=65 WHERE wallet_code=?", sender.walletCode());
        db.update("UPDATE wallets SET balance_dong=37 WHERE wallet_code=?", recipient.walletCode());

        JsonNode firstPage = body(request("GET", "/admin/reconciliation?page=0&size=1", administrator.token(), null));
        JsonNode secondPage = body(request("GET", "/admin/reconciliation?page=1&size=1", administrator.token(), null));
        assertEquals(3, firstPage.path("checkedWallets").longValue());
        assertEquals(2, firstPage.path("mismatchCount").longValue());
        assertEquals(1, firstPage.path("items").size());
        assertEquals(1, secondPage.path("items").size());
        List<JsonNode> mismatches = List.of(firstPage.path("items").get(0), secondPage.path("items").get(0));
        JsonNode senderMismatch = mismatches.stream()
                .filter(item -> item.path("walletCode").asString().equals(sender.walletCode())).findFirst().orElseThrow();
        JsonNode recipientMismatch = mismatches.stream()
                .filter(item -> item.path("walletCode").asString().equals(recipient.walletCode())).findFirst().orElseThrow();
        assertEquals("65", senderMismatch.path("actualBalanceDong").asString());
        assertEquals("60", senderMismatch.path("ledgerBalanceDong").asString());
        assertEquals("5", senderMismatch.path("differenceDong").asString());
        assertEquals("37", recipientMismatch.path("actualBalanceDong").asString());
        assertEquals("40", recipientMismatch.path("ledgerBalanceDong").asString());
        assertEquals("-3", recipientMismatch.path("differenceDong").asString());
        assertEquals(65, wallet(sender).path("balanceDong").longValue());
        assertEquals(37, wallet(recipient).path("balanceDong").longValue());
        assertEquals(0, body(request("GET", "/admin/reconciliation?page=2&size=1",
                administrator.token(), null)).path("items").size());
        assertError(400, "INVALID_REQUEST", request("GET",
                "/admin/reconciliation?page=-1&size=20", administrator.token(), null));
        assertError(400, "INVALID_REQUEST", request("GET",
                "/admin/reconciliation?page=0&size=101", administrator.token(), null));
    }

    @Test
    void userCannotGrantOrReadOtherPeoplesReceipt() throws Exception {
        Account a = register("private-a");
        Account b = register("private-b");
        Account outsider = register("private-c");
        Account admin = admin();
        assertError(403, "FORBIDDEN", request("POST", "/admin/grants", a.token(),
                "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"recipientWalletCode\":\"" + a.walletCode() + "\",\"amountDong\":100,\"reason\":\"test\"}"));
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
    void grantRetryReturnsSameReceiptAndChangedPayloadConflicts() throws Exception {
        Account recipient = register("grant-repeat");
        Account other = register("grant-other");
        Account administrator = admin();
        UUID key = UUID.randomUUID();
        assertError(400, "INVALID_REQUEST", request("POST", "/admin/grants", administrator.token(),
                "{\"recipientWalletCode\":\"" + recipient.walletCode() + "\",\"amountDong\":100,\"reason\":\"Test funding\"}"));
        assertError(400, "INVALID_AMOUNT", grantRaw(administrator, recipient.walletCode(), "0", "Test funding", key));
        HttpResponse<String> first = grantRaw(administrator, recipient.walletCode(), "100", "Test funding", key);
        HttpResponse<String> replay = grantRaw(administrator, recipient.walletCode(), "100", "Test funding", key);
        assertEquals(201, first.statusCode(), first.body());
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(first.body(), replay.body());
        assertEquals(key.toString(), body(first).path("requestKey").asString());
        assertError(409, "GRANT_KEY_CONFLICT", grantRaw(administrator, recipient.walletCode(), "101", "Test funding", key));
        assertError(409, "GRANT_KEY_CONFLICT", grantRaw(administrator, other.walletCode(), "100", "Test funding", key));
        assertError(409, "GRANT_KEY_CONFLICT", grantRaw(administrator, recipient.walletCode(), "100", "Other reason", key));
        assertEquals(100, wallet(recipient).path("balanceDong").longValue());
        assertEquals(0, wallet(other).path("balanceDong").longValue());
        assertEquals(1, count("admin_grants"));
        assertEquals(1, count("ledger_entries"));
    }

    @Test
    void concurrentSameGrantKeyCreatesOnlyOneGrant() throws Exception {
        Account recipient = register("grant-parallel");
        Account administrator = admin();
        UUID key = UUID.randomUUID();
        List<HttpResponse<String>> responses = parallel(
                () -> grantRaw(administrator, recipient.walletCode(), "700", "Parallel demo", key),
                () -> grantRaw(administrator, recipient.walletCode(), "700", "Parallel demo", key));
        assertEquals(1, responses.stream().filter(r -> r.statusCode() == 201).count(), responses.toString());
        assertEquals(1, responses.stream().filter(r -> r.statusCode() == 200).count(), responses.toString());
        assertEquals(responses.get(0).body(), responses.get(1).body());
        assertEquals(700, wallet(recipient).path("balanceDong").longValue());
        assertEquals(1, count("admin_grants"));
        assertEquals(1, count("ledger_entries"));
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
    void pagedStatementsReassembleToTheOriginalCsv() throws Exception {
        Account sender = register("page-sender");
        Account recipient = register("page-recipient");
        grant(admin(), sender, 100);
        String firstId = body(transfer(sender, recipient, 10, UUID.randomUUID())).path("transferId").asString();
        String secondId = body(transfer(sender, recipient, 20, UUID.randomUUID())).path("transferId").asString();
        String thirdId = body(transfer(sender, recipient, 30, UUID.randomUUID())).path("transferId").asString();
        db.update("UPDATE transfers SET created_at='2026-01-01 00:00:00+00' WHERE id=?", UUID.fromString(firstId));
        db.update("UPDATE transfers SET created_at='2026-01-02 00:00:00+00' WHERE id=?", UUID.fromString(secondId));
        db.update("UPDATE transfers SET created_at='2026-01-03 00:00:00+00' WHERE id=?", UUID.fromString(thirdId));
        String path = "/statements?from=2026-01-01&to=2026-12-31&format=csv";
        List<String> all = new String(download(sender, path).body(), StandardCharsets.UTF_8).lines().toList();
        HttpResponse<byte[]> first = download(sender, path + "&page=0&size=2");
        HttpResponse<byte[]> second = download(sender, path + "&page=1&size=2");
        assertEquals(200, first.statusCode());
        assertEquals("true", first.headers().firstValue("X-Has-More").orElseThrow());
        assertEquals("false", second.headers().firstValue("X-Has-More").orElseThrow());
        assertTrue(first.headers().firstValue("Content-Disposition").orElse("").contains("page-1.csv"));
        List<String> firstLines = new String(first.body(), StandardCharsets.UTF_8).lines().toList();
        List<String> secondLines = new String(second.body(), StandardCharsets.UTF_8).lines().toList();
        List<String> combined = new ArrayList<>(firstLines);
        combined.addAll(secondLines.subList(1, secondLines.size()));
        assertEquals(all, combined);
        HttpResponse<byte[]> pdfPage = download(sender,
                "/statements?from=2026-01-01&to=2026-12-31&format=pdf&page=0&size=2");
        assertEquals(200, pdfPage.statusCode());
        assertEquals("true", pdfPage.headers().firstValue("X-Has-More").orElseThrow());
        try (var document = Loader.loadPDF(pdfPage.body())) {
            String pageText = new PDFTextStripper().getText(document);
            assertTrue(pageText.contains("Tổng chuyển đi: 30 VND"));
            assertFalse(pageText.contains("Tổng chuyển đi: 60 VND"));
        }
        assertError(400, "INVALID_PAGE", request("GET", path + "&page=0&size=1001", sender.token(), null));
        assertError(400, "INVALID_PAGE", request("GET", path + "&page=1", sender.token(), null));
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
        assertEquals(List.of("An uong", "Di lai", "Ăn uống", "Đi lại"),
                category.path("items").values().stream().map(item -> item.path("key").asString()).toList());
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
    void csvPreviewShowsAllRowErrorsWithoutWritingAndConfirmationIsIdempotent() throws Exception {
        Account account = register("preview-a");
        byte[] mixed = ("date,description,category,amount_vnd\n"
                + "2026-01-01,Lunch,Food,12000\n"
                + "bad-date,Taxi,Travel,15000\n"
                + "2026-01-03,Shop,Other,12.50\n"
                + "2026-01-04,Coffee,Food,8000\n").getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> preview = preview(account, "SAMPLE_A", mixed);
        assertEquals(200, preview.statusCode(), preview.body());
        JsonNode result = body(preview);
        assertEquals(4, result.path("rowCount").intValue());
        assertFalse(result.path("canImport").booleanValue());
        assertEquals(2, result.path("validRows").size());
        assertEquals(2, result.path("validRows").get(0).path("sourceRow").intValue());
        assertEquals(5, result.path("validRows").get(1).path("sourceRow").intValue());
        assertEquals(2, result.path("errors").size());
        assertEquals(3, result.path("errors").get(0).path("sourceRow").intValue());
        assertEquals("INVALID_CSV", result.path("errors").get(0).path("code").asString());
        assertEquals(4, result.path("errors").get(1).path("sourceRow").intValue());
        assertEquals("INVALID_AMOUNT", result.path("errors").get(1).path("code").asString());
        assertEquals(0, count("import_batches"));
        assertEquals(0, count("imported_expenses"));
        assertError(400, "INVALID_CSV", upload(account, "SAMPLE_A", UUID.randomUUID(), mixed));
        assertEquals(0, count("import_batches"));

        byte[] clean = ("date,description,category,amount_vnd\n"
                + "2026-01-01,Lunch,Food,12000\n"
                + "2026-01-04,Coffee,Food,8000\n").getBytes(StandardCharsets.UTF_8);
        JsonNode cleanPreview = body(preview(account, "SAMPLE_A", clean));
        assertTrue(cleanPreview.path("canImport").booleanValue());
        assertEquals(2, cleanPreview.path("validRows").size());
        assertEquals(0, count("import_batches"));
        UUID key = UUID.randomUUID();
        HttpResponse<String> first = upload(account, "SAMPLE_A", key, clean);
        assertEquals(201, first.statusCode(), first.body());
        HttpResponse<String> retry = upload(account, "SAMPLE_A", key, clean);
        assertEquals(200, retry.statusCode(), retry.body());
        assertEquals(body(first), body(retry));
        assertEquals(1, count("import_batches"));
        assertEquals(2, count("imported_expenses"));
    }

    @Test
    void csvPreviewUsesSecondAdapterAndReportsFileErrors() throws Exception {
        Account account = register("preview-b");
        byte[] sampleB = Files.readAllBytes(Path.of("samples/expenses-b.csv"));
        JsonNode good = body(preview(account, "SAMPLE_B", sampleB));
        assertTrue(good.path("canImport").booleanValue());
        assertEquals(2, good.path("validRows").size());
        byte[] webSample = Files.readAllBytes(Path.of("samples/chi-tieu-mau.csv"));
        assertEquals(0xEF, Byte.toUnsignedInt(webSample[0]));
        assertEquals(0xBB, Byte.toUnsignedInt(webSample[1]));
        assertEquals(0xBF, Byte.toUnsignedInt(webSample[2]));
        JsonNode downloadable = body(preview(account, "SAMPLE_B", webSample));
        assertTrue(downloadable.path("canImport").booleanValue());
        assertEquals(2, downloadable.path("validRows").size());
        assertEquals(0, count("import_batches"));
        JsonNode badHeader = body(preview(account, "SAMPLE_B", "wrong;header\n1;2\n".getBytes(StandardCharsets.UTF_8)));
        assertFalse(badHeader.path("canImport").booleanValue());
        assertEquals(1, badHeader.path("errors").get(0).path("sourceRow").intValue());
        JsonNode badUtf8 = body(preview(account, "SAMPLE_B", new byte[] {(byte) 0xC3, 0x28}));
        assertFalse(badUtf8.path("canImport").booleanValue());
        assertEquals("INVALID_CSV", badUtf8.path("errors").get(0).path("code").asString());
        JsonNode empty = body(preview(account, "SAMPLE_B", new byte[0]));
        assertFalse(empty.path("canImport").booleanValue());
        assertEquals(1, empty.path("errors").size());
        assertEquals(401, preview(null, "SAMPLE_B", sampleB).statusCode());
        assertEquals(0, count("import_batches"));
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

    private HttpResponse<String> preview(Account account, String format, byte[] file) throws Exception {
        String boundary = "test-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"expenses.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(file);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/expense-imports/preview?format=" + format))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (account != null) request.header("Authorization", "Bearer " + account.token());
        return http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    @Test
    void transfersNotifyBothSidesOnlyAfterCommitAndNotOnReplay() throws Exception {
        Account a = register("notify-a");
        Account b = register("notify-b");
        grant(admin(), a, 100);
        assertEquals(1, notifications(a, "GRANT_RECEIVED"));
        UUID key = UUID.randomUUID();
        assertEquals(201, transfer(a, b, 30, key).statusCode());
        assertEquals(200, transfer(a, b, 30, key).statusCode()); // idempotent replay
        assertEquals(1, notifications(a, "TRANSFER_SENT"));
        assertEquals(1, notifications(b, "TRANSFER_RECEIVED"));
        assertEquals(0, notifications(b, "TRANSFER_SENT"));

        FAIL_AFTER_DEBIT.set(true);
        assertEquals(500, transfer(a, b, 20, UUID.randomUUID()).statusCode());
        FAIL_AFTER_DEBIT.set(false);
        assertEquals(1, notifications(a, "TRANSFER_SENT"));
        assertEquals(1, notifications(b, "TRANSFER_RECEIVED"));
    }

    @Test
    void lowBalanceAlertsOnceThenRearmsAfterBalanceRecovers() throws Exception {
        Account a = register("low-a");
        Account b = register("low-b");
        grant(admin(), a, 100);
        String path = "/me/accounts/" + wallet(a).path("walletId").asString() + "/notification-settings";
        assertEquals(200, request("PUT", path, a.token(), "{\"lowBalanceDong\":50}").statusCode());
        assertEquals(50, body(request("GET", path, a.token(), null)).path("lowBalanceDong").longValue());
        assertEquals(0, notifications(a, "LOW_BALANCE"));

        assertEquals(201, transfer(a, b, 60, UUID.randomUUID()).statusCode()); // 40 < 50
        assertEquals(1, notifications(a, "LOW_BALANCE"));
        assertEquals(201, transfer(a, b, 10, UUID.randomUUID()).statusCode()); // 30, already alerted
        assertEquals(1, notifications(a, "LOW_BALANCE"));
        grant(admin(), a, 100); // 130 re-arms
        assertEquals(201, transfer(a, b, 100, UUID.randomUUID()).statusCode()); // 30 again
        assertEquals(2, notifications(a, "LOW_BALANCE"));
        assertEquals(0, notifications(b, "LOW_BALANCE"));

        assertError(400, "INVALID_AMOUNT", request("PUT", path, a.token(), "{\"lowBalanceDong\":-1}"));
        assertError(400, "INVALID_AMOUNT", request("PUT", path, a.token(), "{\"lowBalanceDong\":\"10\"}"));
        assertError(404, "WALLET_NOT_FOUND", request("GET", path, b.token(), null));
    }

    @Test
    void notificationApiIsPrivateAndTracksReadState() throws Exception {
        Account a = register("api-a");
        Account b = register("api-b");
        grant(admin(), a, 100);
        assertEquals(201, transfer(a, b, 10, UUID.randomUUID()).statusCode());
        JsonNode mine = body(request("GET", "/notifications", a.token(), null));
        assertEquals(2, mine.path("total").longValue());
        assertEquals(2, mine.path("unread").longValue());
        String id = mine.path("items").get(0).path("id").asString();
        assertError(404, "NOTIFICATION_NOT_FOUND", request("POST", "/notifications/" + id + "/read", b.token(), null));
        assertEquals(204, request("POST", "/notifications/" + id + "/read", a.token(), null).statusCode());
        assertEquals(1, body(request("GET", "/notifications", a.token(), null)).path("unread").longValue());
        assertEquals(204, request("POST", "/notifications/read-all", a.token(), null).statusCode());
        assertEquals(0, body(request("GET", "/notifications", a.token(), null)).path("unread").longValue());
        assertEquals(1, body(request("GET", "/notifications", b.token(), null)).path("total").longValue());
        assertEquals(401, request("GET", "/notifications", null, null).statusCode());
    }

    @Test
    void feeReminderIsSentOncePerWeek() throws Exception {
        Account a = register("remind-a");
        UUID walletId = UUID.fromString(wallet(a).path("walletId").asString());
        db.update("INSERT INTO account_fees(id,wallet_id,fee_code,period_start,amount_dong) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), walletId, "CHECKING_MONTHLY", java.sql.Date.valueOf("2026-10-01"), 5000);
        LocalDate day = LocalDate.of(2026, 10, 7);
        assertEquals(1, reminders.run(day));
        assertEquals(0, reminders.run(day));
        assertEquals(0, reminders.run(day.plusDays(1)));
        assertEquals(1, notifications(a, "FEE_DUE"));
        assertEquals(1, reminders.run(day.plusWeeks(1)));
        assertEquals(2, notifications(a, "FEE_DUE"));
    }

    @Test
    void creditDebtReminderIsMonthlyAndStopsWhenRepaid() throws Exception {
        Account owner = register("remind-credit");
        Account administrator = admin();
        UUID id = UUID.fromString(body(request("POST", "/me/accounts", owner.token(),
                "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"type\":\"CREDIT\"}")).path("accountId").asString());
        assertEquals(201, request("POST", "/admin/credit-accounts/" + id + "/limit", administrator.token(),
                "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"limitDong\":100000}").statusCode());
        LocalDate day = LocalDate.of(2026, 10, 7);
        assertEquals(0, reminders.run(day)); // no debt yet
        assertEquals(201, request("POST", "/me/accounts/" + id + "/credit/charges", owner.token(),
                "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"amountDong\":60000,\"description\":\"Minh hoa\"}")
                .statusCode());
        assertEquals(1, reminders.run(day));
        assertEquals(0, reminders.run(day.plusDays(10))); // same month
        assertEquals(1, reminders.run(day.plusMonths(1)));
        assertEquals(2, notifications(owner, "CREDIT_DEBT"));
        assertTrue(body(request("GET", "/notifications", owner.token(), null)).path("items").get(0)
                .path("body").asString().contains("60.000"));

        grant(administrator, owner, 60_000);
        assertEquals(201, request("POST", "/me/accounts/" + id + "/credit/repayments", owner.token(),
                "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"sourceAccountId\":\""
                        + wallet(owner).path("walletId").asString() + "\",\"amountDong\":60000}").statusCode());
        assertEquals(0, reminders.run(day.plusMonths(2))); // debt repaid
    }

    @Test
    void savingsMaturityReminderFiresOnlyInsideNoticeWindowAndOnlyOnce() throws Exception {
        Account owner = register("remind-savings");
        grant(admin(), owner, 200_000);
        UUID fundingId = UUID.fromString(wallet(owner).path("walletId").asString());
        UUID id = UUID.fromString(body(request("POST", "/me/accounts", owner.token(),
                "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"type\":\"SAVINGS\","
                        + "\"fundingAccountId\":\"" + fundingId + "\",\"amountDong\":200000}")).path("accountId").asString());
        LocalDate maturity = LocalDate.of(2026, 12, 31);
        db.update("UPDATE savings_accounts SET matures_on=? WHERE wallet_id=?", java.sql.Date.valueOf(maturity), id);
        assertEquals(0, reminders.run(maturity.minusDays(4))); // outside the 3-day window
        assertEquals(1, reminders.run(maturity.minusDays(3)));
        assertEquals(0, reminders.run(maturity.minusDays(1))); // already reminded
        assertEquals(1, notifications(owner, "SAVINGS_MATURING"));
        assertTrue(body(request("GET", "/notifications", owner.token(), null)).path("items").get(0)
                .path("body").asString().contains("2026-12-31"));
    }

    private int notifications(Account account, String type) {
        return db.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND type=?",
                Integer.class, account.userId(), type);
    }

    private void grant(Account admin, Account to, long amount) throws Exception {
        HttpResponse<String> response = grantRaw(admin, to.walletCode(), Long.toString(amount),
                "Test funding", UUID.randomUUID());
        assertEquals(201, response.statusCode(), response.body());
    }

    private HttpResponse<String> grantRaw(Account admin, String recipient, String amountJson,
                                           String reason, UUID key) throws Exception {
        return request("POST", "/admin/grants", admin.token(),
                "{\"requestKey\":\"" + key + "\",\"recipientWalletCode\":\"" + recipient
                        + "\",\"amountDong\":" + amountJson + ",\"reason\":\"" + reason + "\"}");
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
