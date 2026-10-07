package vn.edu.wallet.desktop;

import java.util.Map;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import tools.jackson.databind.JsonNode;

public final class LoginController {
    @FXML private TextField serverUrl;
    @FXML private TextField email;
    @FXML private TextField displayName;
    @FXML private PasswordField password;
    @FXML private VBox registerFields;
    @FXML private Label formTitle;
    @FXML private Label status;
    @FXML private Button submitButton;
    @FXML private Button switchButton;
    private WalletDesktopApp app;
    private WalletApiClient api;
    private boolean registering;

    void init(WalletDesktopApp app, WalletApiClient api) {
        this.app = app;
        this.api = api;
        serverUrl.setText(api.baseUrl());
        showMode();
    }

    @FXML private void switchMode() {
        registering = !registering;
        status.setText("");
        showMode();
    }

    private void showMode() {
        registerFields.setVisible(registering);
        registerFields.setManaged(registering);
        formTitle.setText(registering ? "Tạo tài khoản" : "Chào mừng trở lại");
        submitButton.setText(registering ? "Đăng ký và vào ví" : "Đăng nhập");
        switchButton.setText(registering ? "Đã có tài khoản? Đăng nhập" : "Chưa có tài khoản? Đăng ký");
    }

    @FXML private void submit() {
        String address = email.getText().trim();
        String secret = password.getText();
        if (address.isBlank() || secret.isBlank() || (registering && displayName.getText().isBlank())) {
            UiSupport.status(status, "Vui lòng điền đủ thông tin.", true);
            return;
        }
        try { api = new WalletApiClient(serverUrl.getText()); }
        catch (IllegalArgumentException ex) { UiSupport.status(status, ex.getMessage(), true); return; }
        submitButton.setDisable(true);
        switchButton.setDisable(true);
        UiSupport.status(status, "Đang kết nối server…", false);
        JsonNode login = api.object(Map.of("email", address, "password", secret));
        var future = registering
                ? api.post("/auth/register", api.object(Map.of("email", address, "displayName", displayName.getText().trim(), "password", secret)))
                    .thenCompose(ignored -> api.post("/auth/login", login))
                : api.post("/auth/login", login);
        future.whenComplete((result, failure) -> UiSupport.onUi(() -> {
            submitButton.setDisable(false);
            switchButton.setDisable(false);
            if (failure != null) { UiSupport.status(status, UiSupport.error(failure), true); return; }
            api.useToken(result.path("accessToken").asString());
            password.clear();
            try {
                app.useClient(api);
                app.showWorkspace(result.path("user").path("displayName").asString(),
                        result.path("user").path("role").asString());
            } catch (Exception ex) { UiSupport.status(status, "Không mở được giao diện: " + ex.getMessage(), true); }
        }));
    }
}
