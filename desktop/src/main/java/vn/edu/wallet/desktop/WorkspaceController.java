package vn.edu.wallet.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import tools.jackson.databind.JsonNode;

public final class WorkspaceController {
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.systemDefault());
    @FXML private ScrollPane dashboardScroll, transferScroll, historyScroll, statementsScroll, importsScroll, statsScroll, adminScroll;
    @FXML private Button adminNav;
    @FXML private Label userLabel, connectionLabel, globalStatus, balanceLabel, walletCodeLabel;
    @FXML private ListView<String> recentList, historyList, statsList;
    @FXML private TextField recipientCode, transferAmount, grantWallet, grantAmount;
    @FXML private TextArea grantReason;
    @FXML private Label recipientResult, transferStatus, receiptText, historyPageLabel, detailText;
    @FXML private Label statementStatus, selectedFileLabel, importStatus, statsTotal, statsStatus, grantStatus;
    @FXML private Button lookupButton, confirmTransferButton, retryTransferButton, newTransferButton;
    @FXML private Button previousPageButton, nextPageButton, csvButton, pdfButton, importButton, grantButton;
    @FXML private DatePicker statementFrom, statementTo, statsFrom, statsTo;
    @FXML private ComboBox<String> importFormat;
    private WalletDesktopApp app;
    private WalletApiClient api;
    private String role;
    private JsonNode lookedUp;
    private TransferIntent pendingTransfer;
    private List<JsonNode> historyItems = List.of();
    private int historyPage;
    private long historyTotal;
    private Path importFile;
    private UUID importKey;
    private boolean active = true;

    void init(WalletDesktopApp app, WalletApiClient api, String name, String role) {
        this.app = app;
        this.api = api;
        this.role = role;
        userLabel.setText(name + "  ·  " + role);
        connectionLabel.setText(api.baseUrl());
        adminNav.setVisible("ADMIN".equals(role));
        adminNav.setManaged("ADMIN".equals(role));
        LocalDate today = LocalDate.now();
        statementFrom.setValue(today.withDayOfYear(1));
        statementTo.setValue(today);
        statsFrom.setValue(today.withDayOfYear(1));
        statsTo.setValue(today);
        importFormat.getItems().setAll("SAMPLE_A", "SAMPLE_B");
        importFormat.setValue("SAMPLE_A");
        importFormat.valueProperty().addListener((ignored, before, after) -> importKey = null);
        recipientCode.textProperty().addListener((ignored, before, after) -> {
            if (pendingTransfer == null) { lookedUp = null; recipientResult.setText("Chưa tra cứu"); }
        });
        showDashboard();
    }

    private void show(ScrollPane selected) {
        for (ScrollPane page : List.of(dashboardScroll, transferScroll, historyScroll,
                statementsScroll, importsScroll, statsScroll, adminScroll)) {
            page.setVisible(page == selected);
            page.setManaged(page == selected);
        }
        globalStatus.setText("");
    }

    @FXML private void showDashboard() { show(dashboardScroll); loadDashboard(); }
    @FXML private void showTransfer() { show(transferScroll); }
    @FXML private void showHistory() { show(historyScroll); loadHistory(); }
    @FXML private void showStatements() { show(statementsScroll); }
    @FXML private void showImports() { show(importsScroll); }
    @FXML private void showStats() { show(statsScroll); }
    @FXML private void showAdmin() { if ("ADMIN".equals(role)) show(adminScroll); }

    @FXML private void loadDashboard() {
        UiSupport.status(globalStatus, "Đang tải số dư và giao dịch gần đây…", false);
        CompletableFuture.allOf(api.get("/me/wallet").thenAccept(wallet -> UiSupport.onUi(() -> {
                    if (!active) return;
                    balanceLabel.setText(UiSupport.money(wallet.path("balanceDong").longValue()));
                    walletCodeLabel.setText("Mã ví: " + wallet.path("walletCode").asString());
                })),
                api.get("/transfers?page=0&size=5").thenAccept(page -> UiSupport.onUi(() -> {
                    if (!active) return;
                    recentList.getItems().setAll(lines(page.path("items")));
                    if (recentList.getItems().isEmpty()) recentList.getItems().add("Chưa có giao dịch nào.");
                }))).whenComplete((ignored, failure) -> UiSupport.onUi(() -> {
                    if (active) UiSupport.status(globalStatus, failure == null ? "Dữ liệu đã cập nhật." : UiSupport.error(failure), failure != null);
                }));
    }

    @FXML private void lookupRecipient() {
        String code = recipientCode.getText().trim();
        if (code.isBlank()) { UiSupport.status(transferStatus, "Nhập mã ví người nhận.", true); return; }
        lookupButton.setDisable(true);
        UiSupport.status(transferStatus, "Đang tra cứu ví…", false);
        api.get("/wallets/lookup/" + WalletApiClient.encode(code))
                .whenComplete((result, failure) -> UiSupport.onUi(() -> {
                    if (!active) return;
                    lookupButton.setDisable(false);
                    if (failure != null) {
                        lookedUp = null;
                        recipientResult.setText("Chưa xác minh người nhận");
                        UiSupport.status(transferStatus, UiSupport.error(failure), true);
                    } else {
                        lookedUp = result;
                        recipientResult.setText("Người nhận: " + result.path("displayName").asString()
                                + "  ·  " + result.path("walletCode").asString());
                        UiSupport.status(transferStatus, "Đã xác minh mã ví. Không hiển thị số dư người nhận.", false);
                    }
                }));
    }

    @FXML private void confirmTransfer() {
        if (pendingTransfer != null) {
            UiSupport.status(transferStatus, "Yêu cầu trước chưa rõ kết quả. Dùng “Thử lại yêu cầu cũ”.", true);
            return;
        }
        Long amount = amount(transferAmount.getText());
        if (amount == null) { UiSupport.status(transferStatus, "Số tiền phải là số nguyên từ 1 đến 1.000.000.000.000 VND.", true); return; }
        if (lookedUp == null || !lookedUp.path("walletCode").asString().equals(recipientCode.getText().trim())) {
            UiSupport.status(transferStatus, "Hãy tra cứu lại đúng mã ví người nhận trước khi xác nhận.", true);
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Xác nhận chuyển tiền");
        confirm.setHeaderText("Kiểm tra giao dịch trước khi gửi");
        confirm.setContentText("Người nhận: " + lookedUp.path("displayName").asString() + "\nMã ví: "
                + lookedUp.path("walletCode").asString() + "\nSố tiền: " + UiSupport.money(amount));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        pendingTransfer = new TransferIntent(UUID.randomUUID(), lookedUp.path("walletCode").asString(), amount);
        lockTransfer(true);
        sendPendingTransfer();
    }

    @FXML private void retryTransfer() { if (pendingTransfer != null) sendPendingTransfer(); }

    private void sendPendingTransfer() {
        TransferIntent intent = pendingTransfer;
        if (intent == null) return;
        retryTransferButton.setDisable(true);
        UiSupport.status(transferStatus, "Đang gửi yêu cầu chuyển tiền…", false);
        api.post("/transfers", api.object(Map.of("requestKey", intent.key().toString(),
                "recipientWalletCode", intent.recipientCode(), "amountDong", intent.amountDong())))
                .whenComplete((receipt, failure) -> UiSupport.onUi(() -> {
                    if (!active || pendingTransfer != intent) return;
                    retryTransferButton.setDisable(false);
                    if (failure == null) {
                        pendingTransfer = null;
                        retryTransferButton.setVisible(false);
                        retryTransferButton.setManaged(false);
                        newTransferButton.setVisible(true);
                        newTransferButton.setManaged(true);
                        receiptText.setText(receipt(receipt));
                        receiptCard(true);
                        UiSupport.status(transferStatus, "Chuyển tiền thành công. Số dư và lịch sử đang được tải lại từ server.", false);
                        loadDashboard();
                        loadHistory();
                    } else if (UiSupport.uncertain(failure)) {
                        retryTransferButton.setVisible(true);
                        retryTransferButton.setManaged(true);
                        UiSupport.status(transferStatus,
                                "Chưa biết server đã xử lý hay chưa. “Thử lại yêu cầu cũ” sẽ giữ nguyên mã yêu cầu và nội dung. "
                                        + UiSupport.error(failure), true);
                    } else {
                        pendingTransfer = null;
                        lockTransfer(false);
                        UiSupport.status(transferStatus, UiSupport.error(failure), true);
                    }
                }));
    }

    private void lockTransfer(boolean locked) {
        recipientCode.setDisable(locked);
        transferAmount.setDisable(locked);
        lookupButton.setDisable(locked);
        confirmTransferButton.setDisable(locked);
    }

    @FXML private void newTransfer() {
        if (pendingTransfer != null) return;
        receiptCard(false);
        newTransferButton.setVisible(false);
        newTransferButton.setManaged(false);
        retryTransferButton.setVisible(false);
        retryTransferButton.setManaged(false);
        recipientCode.clear();
        transferAmount.clear();
        lookedUp = null;
        recipientResult.setText("Chưa tra cứu");
        transferStatus.setText("");
        lockTransfer(false);
    }

    @FXML private void previousHistory() { if (historyPage > 0) { historyPage--; loadHistory(); } }
    @FXML private void nextHistory() { if ((historyPage + 1L) * 20 < historyTotal) { historyPage++; loadHistory(); } }

    private void loadHistory() {
        historyPageLabel.setText("Đang tải trang " + (historyPage + 1) + "…");
        api.get("/transfers?page=" + historyPage + "&size=20").whenComplete((result, failure) -> UiSupport.onUi(() -> {
            if (!active) return;
            if (failure != null) { UiSupport.status(globalStatus, UiSupport.error(failure), true); return; }
            historyItems = result.path("items").values().stream().toList();
            historyTotal = result.path("totalItems").longValue();
            historyList.getItems().setAll(lines(result.path("items")));
            if (historyItems.isEmpty()) historyList.getItems().add("Chưa có giao dịch ở trang này.");
            historyPageLabel.setText("Trang " + (historyPage + 1) + " · " + historyTotal + " giao dịch");
            previousPageButton.setDisable(historyPage == 0);
            nextPageButton.setDisable((historyPage + 1L) * 20 >= historyTotal);
        }));
    }

    @FXML private void viewTransferDetail() {
        int index = historyList.getSelectionModel().getSelectedIndex();
        if (index < 0 || index >= historyItems.size()) { detailText.setText("Chọn một giao dịch để xem chi tiết."); return; }
        String id = historyItems.get(index).path("transferId").asString();
        detailText.setText("Đang tải biên nhận…");
        api.get("/transfers/" + WalletApiClient.encode(id)).whenComplete((result, failure) -> UiSupport.onUi(() -> {
            if (active) detailText.setText(failure == null ? receipt(result) : UiSupport.error(failure));
        }));
    }

    @FXML private void downloadCsv() { downloadStatement("csv"); }
    @FXML private void downloadPdf() { downloadStatement("pdf"); }

    private void downloadStatement(String format) {
        if (!validRange(statementFrom.getValue(), statementTo.getValue(), statementStatus)) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Lưu sao kê " + format.toUpperCase());
        chooser.setInitialFileName("statement-" + statementFrom.getValue().toString().replace("-", "")
                + "-" + statementTo.getValue().toString().replace("-", "") + "." + format);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(format.toUpperCase(), "*." + format));
        var file = chooser.showSaveDialog(csvButton.getScene().getWindow());
        if (file == null) return;
        csvButton.setDisable(true); pdfButton.setDisable(true);
        UiSupport.status(statementStatus, "Đang tải sao kê…", false);
        String path = "/statements?from=" + statementFrom.getValue() + "&to=" + statementTo.getValue() + "&format=" + format;
        api.download(path).thenApplyAsync(bytes -> {
            try { Files.write(file.toPath(), bytes); return file.toPath(); }
            catch (Exception ex) { throw new RuntimeException(ex); }
        }).whenComplete((saved, failure) -> UiSupport.onUi(() -> {
            if (!active) return;
            csvButton.setDisable(false); pdfButton.setDisable(false);
            UiSupport.status(statementStatus, failure == null ? "Đã lưu sao kê: " + saved : UiSupport.error(failure), failure != null);
        }));
    }

    @FXML private void chooseImportFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn tệp CSV chi tiêu");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        var selected = chooser.showOpenDialog(importButton.getScene().getWindow());
        if (selected == null) return;
        importFile = selected.toPath();
        importKey = null;
        selectedFileLabel.setText(selected.getName());
        importStatus.setText("");
    }

    @FXML private void uploadImport() {
        if (importFile == null) { UiSupport.status(importStatus, "Hãy chọn tệp CSV trước.", true); return; }
        if (importKey == null) importKey = UUID.randomUUID();
        UUID key = importKey;
        Path file = importFile;
        String format = importFormat.getValue();
        importButton.setDisable(true);
        UiSupport.status(importStatus, "Đang kiểm tra và nhập CSV…", false);
        CompletableFuture.supplyAsync(() -> api.upload(format, key, file)).thenCompose(f -> f)
                .whenComplete((result, failure) -> UiSupport.onUi(() -> {
                    if (!active) return;
                    importButton.setDisable(false);
                    if (failure == null) {
                        UiSupport.status(importStatus, "Đã nhập " + result.path("rowCount").intValue()
                                + " dòng · Batch " + result.path("batchId").asString() + ".", false);
                        importKey = null;
                    } else {
                        UiSupport.status(importStatus, UiSupport.error(failure), true);
                        if (!UiSupport.uncertain(failure)) importKey = null;
                    }
                }));
    }

    @FXML private void statsByCategory() { loadStats("category"); }
    @FXML private void statsByMonth() { loadStats("month"); }

    private void loadStats(String groupBy) {
        if (!validRange(statsFrom.getValue(), statsTo.getValue(), statsStatus)) return;
        UiSupport.status(statsStatus, "Đang tổng hợp…", false);
        api.get("/expense-stats?from=" + statsFrom.getValue() + "&to=" + statsTo.getValue()
                + "&groupBy=" + groupBy).whenComplete((result, failure) -> UiSupport.onUi(() -> {
            if (!active) return;
            if (failure != null) { UiSupport.status(statsStatus, UiSupport.error(failure), true); return; }
            statsTotal.setText("Tổng " + UiSupport.money(result.path("totalAmountDong").longValue())
                    + "  ·  " + result.path("totalCount").longValue() + " khoản chi");
            statsList.getItems().clear();
            for (JsonNode item : result.path("items")) {
                statsList.getItems().add(item.path("key").asString() + "     "
                        + UiSupport.money(item.path("amountDong").longValue()) + "     ("
                        + item.path("count").longValue() + " khoản)");
            }
            if (statsList.getItems().isEmpty()) statsList.getItems().add("Chưa có dữ liệu trong khoảng ngày này.");
            UiSupport.status(statsStatus, "Đã tải thống kê theo " + (groupBy.equals("category") ? "danh mục." : "tháng."), false);
        }));
    }

    @FXML private void grantBalance() {
        if (!"ADMIN".equals(role)) return;
        Long value = amount(grantAmount.getText());
        if (value == null || grantWallet.getText().isBlank() || grantReason.getText().isBlank()) {
            UiSupport.status(grantStatus, "Nhập mã ví, số tiền nguyên đồng và lý do.", true); return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Xác nhận cấp số dư");
        confirm.setHeaderText("Cấp " + UiSupport.money(value) + " cho " + grantWallet.getText().trim());
        confirm.setContentText("Lý do: " + grantReason.getText().trim() + "\nYêu cầu này không tự gửi lại nếu hết thời gian chờ.");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        grantButton.setDisable(true);
        UiSupport.status(grantStatus, "Đang cấp số dư…", false);
        api.post("/admin/grants", api.object(Map.of("recipientWalletCode", grantWallet.getText().trim(),
                "amountDong", value, "reason", grantReason.getText().trim())))
                .whenComplete((result, failure) -> UiSupport.onUi(() -> {
                    if (!active) return;
                    grantButton.setDisable(false);
                    if (failure == null) {
                        UiSupport.status(grantStatus, "Đã cấp " + UiSupport.money(result.path("amountDong").longValue())
                                + ". Mã thao tác: " + result.path("grantId").asString(), false);
                        grantAmount.clear(); grantReason.clear();
                        loadDashboard();
                    } else if (UiSupport.uncertain(failure)) {
                        UiSupport.status(grantStatus, "Chưa rõ kết quả cấp tiền. Không gửi lại tự động; kiểm tra audit trước. "
                                + UiSupport.error(failure), true);
                    } else UiSupport.status(grantStatus, UiSupport.error(failure), true);
                }));
    }

    @FXML private void logout() {
        active = false;
        api.post("/auth/logout", null).whenComplete((ignored, failure) -> UiSupport.onUi(() -> {
            api.clearToken();
            try { app.showLogin(); }
            catch (Exception ex) { UiSupport.alert("Lỗi giao diện", ex.getMessage()); }
        }));
    }

    private boolean validRange(LocalDate from, LocalDate to, Label target) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= 366) {
            UiSupport.status(target, "Khoảng ngày phải tăng dần và không quá 366 ngày.", true);
            return false;
        }
        return true;
    }

    private static Long amount(String raw) {
        if (raw == null || !raw.trim().matches("[1-9][0-9]*")) return null;
        try {
            long value = Long.parseLong(raw.trim());
            return value <= 1_000_000_000_000L ? value : null;
        } catch (NumberFormatException ex) { return null; }
    }

    private static List<String> lines(JsonNode items) {
        return items.values().stream().map(item -> {
            String direction = item.path("direction").asString();
            String counterparty = direction.equals("OUTGOING") ? item.path("recipientWalletCode").asString()
                    : item.path("senderWalletCode").asString();
            return (direction.equals("OUTGOING") ? "Chuyển đi  −" : "Nhận vào  +")
                    + UiSupport.money(item.path("amountDong").longValue()) + "     ·     " + counterparty
                    + "     ·     " + time(item.path("createdAt").asString());
        }).toList();
    }

    private static String receipt(JsonNode data) {
        return "Mã giao dịch: " + data.path("transferId").asString()
                + "\nThời gian: " + time(data.path("createdAt").asString())
                + "\nTừ ví: " + data.path("senderWalletCode").asString()
                + "\nĐến ví: " + data.path("recipientWalletCode").asString()
                + "\nSố tiền: " + UiSupport.money(data.path("amountDong").longValue())
                + "\nSố dư ví của bạn sau giao dịch: " + UiSupport.money(data.path("myBalanceAfterDong").longValue());
    }

    private static String time(String iso) {
        try { return DISPLAY_TIME.format(Instant.parse(iso)); }
        catch (Exception ex) { return iso; }
    }

    private void receiptCard(boolean visible) {
        var card = receiptText.getParent();
        card.setVisible(visible);
        card.setManaged(visible);
    }

    private record TransferIntent(UUID key, String recipientCode, long amountDong) {}
}
