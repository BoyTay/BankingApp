package vn.edu.wallet.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Opt-in smoke test: run only against a disposable local API/database. */
class DesktopApiIT {
    private static boolean javafxStarted;

    @BeforeAll static void startToolkit() throws Exception {
        if (!javafxStarted) {
            Platform.startup(() -> {});
            Platform.setImplicitExit(false);
            javafxStarted = true;
        }
    }

    @AfterAll static void stopToolkit() { if (javafxStarted) Platform.exit(); }

    @Test void completeApiAndScreensRenderWithRealServer() throws Exception {
        String url = System.getProperty("it.api.url", "http://localhost:8080/api/v1");
        WalletApiClient user = new WalletApiClient(url);
        WalletApiClient admin = new WalletApiClient(url);
        UiFixture loginScreen = onFx(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/login.fxml"));
                Parent root = loader.load();
                Stage stage = new Stage();
                Scene scene = new Scene(root, 1180, 780);
                scene.getStylesheets().add(getClass().getResource("/css/wallet.css").toExternalForm());
                stage.setScene(scene);
                loader.<LoginController>getController().init(null, user);
                stage.show();
                return new UiFixture(stage, scene, root);
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        capture(loginScreen.scene, "login.png");
        onFx(() -> { loginScreen.stage.close(); return null; });
        String suffix = UUID.randomUUID().toString();
        String password = "Demo-" + UUID.randomUUID() + "Aa1!";
        String email = "demo-" + suffix + "@example.test";
        JsonNode registered = user.post("/auth/register", user.object(Map.of(
                "email", email, "displayName", "Tài khoản mẫu", "password", password))).get(25, TimeUnit.SECONDS);
        String walletCode = registered.path("wallet").path("walletCode").asString();
        JsonNode loggedIn = user.post("/auth/login", user.object(Map.of("email", email, "password", password)))
                .get(25, TimeUnit.SECONDS);
        user.useToken(loggedIn.path("accessToken").asString());
        assertEquals(0, user.get("/me/wallet").get(25, TimeUnit.SECONDS).path("balanceDong").longValue());

        String adminEmail = System.getenv("DEMO_ADMIN_EMAIL");
        String adminPassword = System.getenv("DEMO_ADMIN_PASSWORD");
        assertNotNull(adminEmail, "Set DEMO_ADMIN_EMAIL only for this disposable smoke run");
        assertNotNull(adminPassword, "Set DEMO_ADMIN_PASSWORD only for this disposable smoke run");
        JsonNode adminLogin = admin.post("/auth/login", admin.object(Map.of("email", adminEmail,
                "password", adminPassword))).get(25, TimeUnit.SECONDS);
        admin.useToken(adminLogin.path("accessToken").asString());
        assertEquals("ADMIN", adminLogin.path("user").path("role").asString());
        admin.post("/admin/grants", admin.object(Map.of("recipientWalletCode", walletCode,
                "amountDong", 500_000, "reason", "Cấp tiền cho demo giao diện"))).get(25, TimeUnit.SECONDS);
        String adminWalletCode = admin.get("/me/wallet").get(25, TimeUnit.SECONDS).path("walletCode").asString();
        JsonNode lookup = user.get("/wallets/lookup/" + adminWalletCode).get(25, TimeUnit.SECONDS);
        assertFalse(lookup.has("balanceDong"));
        JsonNode receipt = user.post("/transfers", user.object(Map.of("requestKey", UUID.randomUUID().toString(),
                "recipientWalletCode", adminWalletCode, "amountDong", 12_500))).get(25, TimeUnit.SECONDS);
        assertEquals(487_500, receipt.path("myBalanceAfterDong").longValue());
        assertEquals(1, user.get("/transfers?page=0&size=20").get(25, TimeUnit.SECONDS)
                .path("totalItems").longValue());
        assertEquals(receipt.path("transferId").asString(), user.get("/transfers/" + receipt.path("transferId").asString())
                .get(25, TimeUnit.SECONDS).path("transferId").asString());
        byte[] csv = user.download("/statements?from=" + LocalDate.now().minusDays(1)
                + "&to=" + LocalDate.now() + "&format=csv").get(25, TimeUnit.SECONDS);
        assertTrue(new String(csv, java.nio.charset.StandardCharsets.UTF_8).contains(receipt.path("transferId").asString()));
        byte[] pdf = user.download("/statements?from=" + LocalDate.now().minusDays(1)
                + "&to=" + LocalDate.now() + "&format=pdf").get(25, TimeUnit.SECONDS);
        assertEquals("%PDF-", new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII));
        Path sample = Path.of("..", "samples", "expenses-a.csv");
        assertEquals(2, user.upload("SAMPLE_A", UUID.randomUUID(), sample).get(25, TimeUnit.SECONDS).path("rowCount").intValue());
        assertEquals(57_000, user.get("/expense-stats?from=2026-01-01&to=2026-12-31&groupBy=category")
                .get(25, TimeUnit.SECONDS).path("totalAmountDong").longValue());

        Path savedDirectory = Files.createDirectories(Path.of("target", "demo", "files"));
        UiFixture fixture = onFx(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/workspace.fxml"));
                Parent root = loader.load();
                Stage stage = new Stage();
                stage.setTitle("BankingApp · Demo");
                Scene scene = new Scene(root, 1180, 780);
                scene.getStylesheets().add(getClass().getResource("/css/wallet.css").toExternalForm());
                stage.setScene(scene);
                WorkspaceController controller = loader.getController();
                controller.useFileDialogs(new WorkspaceController.FileDialogs() {
                    public java.io.File save(javafx.stage.FileChooser chooser, Window owner) {
                        return savedDirectory.resolve(chooser.getInitialFileName()).toFile();
                    }
                    public java.io.File open(javafx.stage.FileChooser chooser, Window owner) {
                        return Path.of("..", "samples", "expenses-b.csv").toAbsolutePath().toFile();
                    }
                });
                controller.init(null, user, "Tài khoản mẫu", "USER");
                stage.show();
                return new UiFixture(stage, scene, root);
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        await(() -> !onFx(() -> ((Label) fixture.scene.lookup("#balanceLabel")).getText()).equals("—"));
        assertTrue(onFx(() -> ((Label) fixture.scene.lookup("#balanceLabel")).getText()).contains("487.500"));
        await(() -> onFx(() -> ((ListView<?>) fixture.scene.lookup("#recentList")).getItems().size()) == 1);
        assertFalse(onFx(() -> fixture.scene.lookup("#recentEmptyLabel").isVisible()));
        capture(fixture.scene, "dashboard.png");
        onFx(() -> { button(fixture.root, "Chuyển tiền").fire(); return null; });
        assertTrue(onFx(() -> fixture.scene.lookup("#transferPage").isVisible()));
        capture(fixture.scene, "transfer.png");
        onFx(() -> {
            ((TextField) fixture.scene.lookup("#recipientCode")).setText(adminWalletCode);
            ((TextField) fixture.scene.lookup("#transferAmount")).setText("1000");
            button(fixture.root, "Tra cứu").fire();
            return null;
        });
        await(() -> onFx(() -> ((Label) fixture.scene.lookup("#recipientResult")).getText()).startsWith("Người nhận:"));
        onFx(() -> {
            acceptNextConfirmation();
            button(fixture.root, "Xem xác nhận").fire();
            return null;
        });
        await(() -> onFx(() -> ((Label) fixture.scene.lookup("#receiptText")).getText()).contains("486.500"));
        assertEquals(486_500, user.get("/me/wallet").get(25, TimeUnit.SECONDS).path("balanceDong").longValue());
        onFx(() -> { ((ScrollPane) fixture.scene.lookup("#transferScroll")).setVvalue(1); return null; });
        capture(fixture.scene, "receipt.png");
        onFx(() -> { button(fixture.root, "Lịch sử").fire(); return null; });
        await(() -> onFx(() -> ((ListView<?>) fixture.scene.lookup("#historyList")).getItems().size()) > 0);
        capture(fixture.scene, "history.png");
        onFx(() -> {
            ((ListView<?>) fixture.scene.lookup("#historyList")).getSelectionModel().select(0);
            button(fixture.root, "Xem chi tiết").fire();
            return null;
        });
        await(() -> onFx(() -> ((Label) fixture.scene.lookup("#detailText")).getText()).contains("Mã giao dịch:"));
        onFx(() -> { button(fixture.root, "Thống kê chi tiêu").fire(); return null; });
        onFx(() -> { button(fixture.root, "Theo danh mục").fire(); return null; });
        await(() -> onFx(() -> ((Label) fixture.scene.lookup("#statsTotal")).getText()).contains("57.000"));
        capture(fixture.scene, "statistics.png");
        onFx(() -> { button(fixture.root, "Theo tháng").fire(); return null; });
        await(() -> onFx(() -> ((ListView<?>) fixture.scene.lookup("#statsList")).getItems().toString()).contains("2026-01"));
        onFx(() -> { button(fixture.root, "Sao kê chuyển tiền").fire(); return null; });
        assertTrue(onFx(() -> fixture.scene.lookup("#statementsPage").isVisible()));
        onFx(() -> { button(fixture.root, "Tải CSV").fire(); return null; });
        await(() -> onFx(() -> ((Label) fixture.scene.lookup("#statementStatus")).getText()).startsWith("Đã lưu sao kê:"));
        Path savedCsv = savedDirectory.resolve("statement-" + LocalDate.now().withDayOfYear(1).toString().replace("-", "")
                + "-" + LocalDate.now().toString().replace("-", "") + ".csv");
        assertTrue(Files.readString(savedCsv).contains(receipt.path("transferId").asString()));
        onFx(() -> { button(fixture.root, "Tải PDF").fire(); return null; });
        await(() -> onFx(() -> ((Label) fixture.scene.lookup("#statementStatus")).getText()).endsWith(".pdf"));
        Path savedPdf = savedDirectory.resolve(savedCsv.getFileName().toString().replace(".csv", ".pdf"));
        assertEquals("%PDF-", new String(Files.readAllBytes(savedPdf), 0, 5, StandardCharsets.US_ASCII));
        capture(fixture.scene, "statements.png");
        onFx(() -> { button(fixture.root, "Nhập chi tiêu CSV").fire(); return null; });
        assertTrue(onFx(() -> fixture.scene.lookup("#importsPage").isVisible()));
        onFx(() -> {
            ((javafx.scene.control.ComboBox<String>) fixture.scene.lookup("#importFormat")).setValue("SAMPLE_B");
            button(fixture.root, "Chọn tệp CSV").fire();
            return null;
        });
        assertEquals("expenses-b.csv", onFx(() -> ((Label) fixture.scene.lookup("#selectedFileLabel")).getText()));
        onFx(() -> { button(fixture.root, "Nhập dữ liệu").fire(); return null; });
        await(() -> onFx(() -> ((Label) fixture.scene.lookup("#importStatus")).getText()).startsWith("Đã nhập 2 dòng"));
        capture(fixture.scene, "import.png");
        assertEquals(486_500, user.get("/me/wallet").get(25, TimeUnit.SECONDS).path("balanceDong").longValue());
        onFx(() -> { button(fixture.root, "Thống kê chi tiêu").fire(); button(fixture.root, "Theo danh mục").fire(); return null; });
        await(() -> onFx(() -> ((Label) fixture.scene.lookup("#statsTotal")).getText()).contains("100.000"));
        capture(fixture.scene, "statistics-imported.png");
        assertFalse(onFx(() -> fixture.scene.lookup("#adminNav").isVisible()));
        onFx(() -> { fixture.stage.close(); return null; });

        UiFixture adminFixture = onFx(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/workspace.fxml"));
                Parent root = loader.load();
                Stage stage = new Stage();
                Scene scene = new Scene(root, 1180, 780);
                scene.getStylesheets().add(getClass().getResource("/css/wallet.css").toExternalForm());
                stage.setScene(scene);
                loader.<WorkspaceController>getController().init(null, admin, "Quản trị mẫu", "ADMIN");
                stage.show();
                return new UiFixture(stage, scene, root);
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        assertTrue(onFx(() -> adminFixture.scene.lookup("#adminNav").isVisible()));
        onFx(() -> { button(adminFixture.root, "Cấp số dư thử nghiệm").fire(); return null; });
        onFx(() -> {
            ((TextField) adminFixture.scene.lookup("#grantWallet")).setText(walletCode);
            ((TextField) adminFixture.scene.lookup("#grantAmount")).setText("7000");
            ((javafx.scene.control.TextArea) adminFixture.scene.lookup("#grantReason")).setText("Kiểm tra thao tác giao diện");
            acceptNextConfirmation();
            button(adminFixture.root, "Xác nhận cấp tiền").fire();
            return null;
        });
        await(() -> onFx(() -> ((Label) adminFixture.scene.lookup("#grantStatus")).getText()).startsWith("Đã cấp 7.000"));
        assertEquals(493_500, user.get("/me/wallet").get(25, TimeUnit.SECONDS).path("balanceDong").longValue());
        capture(adminFixture.scene, "admin.png");
        onFx(() -> { adminFixture.stage.close(); return null; });
        user.post("/auth/logout", null).get(25, TimeUnit.SECONDS);
        user.clearToken();
        admin.post("/auth/logout", null).get(25, TimeUnit.SECONDS);
        admin.clearToken();
    }

    @Test void registerLoginAndLogoutThroughVisibleForms() throws Exception {
        String url = System.getProperty("it.api.url", "http://localhost:8080/api/v1");
        System.setProperty("wallet.api.url", url);
        String email = "ui-" + UUID.randomUUID() + "@example.test";
        String password = "Demo-" + UUID.randomUUID() + "Aa1!";
        Stage stage = onFx(() -> {
            try {
                Stage window = new Stage();
                new WalletDesktopApp().start(window);
                return window;
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        try {
            onFx(() -> {
                Parent root = stage.getScene().getRoot();
                button(root, "Chưa có tài khoản? Đăng ký").fire();
                ((TextField) stage.getScene().lookup("#email")).setText(email);
                ((TextField) stage.getScene().lookup("#displayName")).setText("Người dùng giao diện");
                ((javafx.scene.control.PasswordField) stage.getScene().lookup("#password")).setText(password);
                button(root, "Đăng ký và vào ví").fire();
                return null;
            });
            await(() -> onFx(() -> stage.getScene().lookup("#dashboardScroll") != null));
            assertTrue(onFx(() -> stage.getScene().lookup("#adminNav") != null
                    && !stage.getScene().lookup("#adminNav").isVisible()));
            onFx(() -> { button(stage.getScene().getRoot(), "Đăng xuất").fire(); return null; });
            await(() -> onFx(() -> stage.getScene().lookup("#serverUrl") != null));
            onFx(() -> {
                ((TextField) stage.getScene().lookup("#email")).setText(email);
                ((javafx.scene.control.PasswordField) stage.getScene().lookup("#password")).setText(password);
                button(stage.getScene().getRoot(), "Đăng nhập").fire();
                return null;
            });
            await(() -> onFx(() -> stage.getScene().lookup("#dashboardScroll") != null));
            await(() -> !onFx(() -> ((Label) stage.getScene().lookup("#balanceLabel")).getText()).equals("—"));
            onFx(() -> { button(stage.getScene().getRoot(), "Đăng xuất").fire(); return null; });
            await(() -> onFx(() -> stage.getScene().lookup("#serverUrl") != null));
        } finally {
            onFx(() -> { stage.close(); return null; });
            System.clearProperty("wallet.api.url");
        }
    }

    @Test void retryAfterUncertainResponseKeepsSameRequestKeyAndPayload() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> posts = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/api/v1/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String response;
            int status = 200;
            if (path.equals("/api/v1/me/wallet")) response = "{\"walletCode\":\"WLT_SOURCE\",\"balanceDong\":10000}";
            else if (path.equals("/api/v1/transfers") && exchange.getRequestMethod().equals("GET"))
                response = "{\"items\":[],\"totalItems\":0}";
            else if (path.startsWith("/api/v1/wallets/lookup/"))
                response = "{\"walletCode\":\"WLT_TARGET\",\"displayName\":\"Người nhận mẫu\"}";
            else if (path.equals("/api/v1/transfers") && exchange.getRequestMethod().equals("POST")) {
                posts.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (attempts.incrementAndGet() == 1) {
                    status = 503;
                    response = "{\"code\":\"SERVER_UNAVAILABLE\",\"message\":\"Server tạm bận\"}";
                } else {
                    response = "{\"transferId\":\"" + UUID.randomUUID() + "\",\"requestKey\":\""
                            + new tools.jackson.databind.ObjectMapper().readTree(posts.getFirst()).path("requestKey").asString()
                            + "\",\"senderWalletCode\":\"WLT_SOURCE\",\"recipientWalletCode\":\"WLT_TARGET\","
                            + "\"amountDong\":500,\"direction\":\"OUTGOING\",\"myBalanceAfterDong\":9500,"
                            + "\"createdAt\":\"2026-10-07T00:00:00Z\"}";
                }
            } else { status = 404; response = "{}"; }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        UiFixture fixture = null;
        try {
            WalletApiClient api = new WalletApiClient("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
            fixture = onFx(() -> {
                try {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/workspace.fxml"));
                    Parent root = loader.load();
                    Stage stage = new Stage();
                    Scene scene = new Scene(root, 1180, 780);
                    scene.getStylesheets().add(getClass().getResource("/css/wallet.css").toExternalForm());
                    stage.setScene(scene);
                    loader.<WorkspaceController>getController().init(null, api, "Người dùng mẫu", "USER");
                    stage.show();
                    return new UiFixture(stage, scene, root);
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            UiFixture current = fixture;
            await(() -> onFx(() -> current.scene.lookup("#recentEmptyLabel").isVisible()));
            assertEquals(0, onFx(() -> ((ListView<?>) current.scene.lookup("#recentList")).getItems().size()));
            assertFalse(onFx(() -> current.scene.lookup("#recentList").isVisible()));
            onFx(() -> {
                button(current.root, "Chuyển tiền").fire();
                ((TextField) current.scene.lookup("#recipientCode")).setText("WLT_TARGET");
                ((TextField) current.scene.lookup("#transferAmount")).setText("500");
                button(current.root, "Tra cứu").fire();
                return null;
            });
            await(() -> onFx(() -> ((Label) current.scene.lookup("#recipientResult")).getText()).startsWith("Người nhận:"));
            onFx(() -> { acceptNextConfirmation(); button(current.root, "Xem xác nhận").fire(); return null; });
            await(() -> onFx(() -> ((Button) current.scene.lookup("#retryTransferButton")).isVisible()));
            onFx(() -> { button(current.root, "Thử lại yêu cầu cũ").fire(); return null; });
            await(() -> onFx(() -> ((Label) current.scene.lookup("#receiptText")).getText()).contains("9.500"));
            assertEquals(2, posts.size());
            assertEquals(posts.get(0), posts.get(1));
            assertNotNull(UUID.fromString(new tools.jackson.databind.ObjectMapper().readTree(posts.getFirst())
                    .path("requestKey").asString()));
        } finally {
            if (fixture != null) { UiFixture current = fixture; onFx(() -> { current.stage.close(); return null; }); }
            server.stop(0);
        }
    }

    private static void acceptNextConfirmation() {
        PauseTransition accept = new PauseTransition(javafx.util.Duration.millis(300));
        accept.setOnFinished(event -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window.isShowing() && window.getScene().getRoot() instanceof DialogPane pane) {
                    Node ok = pane.lookupButton(ButtonType.OK);
                    if (ok instanceof Button button) { button.fire(); break; }
                }
            }
        });
        accept.play();
    }

    private static Button button(Parent parent, String text) {
        for (Node node : parent.lookupAll(".button")) {
            if (node instanceof Button b && text.equals(b.getText())) return b;
        }
        return null;
    }

    private static void capture(Scene scene, String name) throws Exception {
        WritableImage image = onFx(() -> scene.snapshot(new WritableImage(1180, 780)));
        BufferedImage output = new BufferedImage(1180, 780, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 780; y++) for (int x = 0; x < 1180; x++)
            output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Path directory = Path.of(System.getProperty("demo.capture.dir", "target/demo"));
        Files.createDirectories(directory);
        ImageIO.write(output, "png", directory.resolve(name).toFile());
    }

    private static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.get()) return;
            Thread.sleep(100);
        }
        fail("UI did not reach expected state");
    }

    @FunctionalInterface private interface CheckedCondition { boolean get() throws Exception; }

    private static <T> T onFx(Supplier<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { result.complete(action.get()); }
            catch (Throwable ex) { result.completeExceptionally(ex); }
        });
        return result.get(20, TimeUnit.SECONDS);
    }

    private record UiFixture(Stage stage, Scene scene, Parent root) {}
}
