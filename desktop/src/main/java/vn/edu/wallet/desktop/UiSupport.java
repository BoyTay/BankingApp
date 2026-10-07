package vn.edu.wallet.desktop;

import java.io.IOException;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;

final class UiSupport {
    private static final NumberFormat VND = NumberFormat.getIntegerInstance(Locale.of("vi", "VN"));
    private UiSupport() {}

    static String money(long amount) { return VND.format(amount) + " ₫"; }
    static String money(String amount) { return VND.format(new BigInteger(amount)) + " ₫"; }
    static void onUi(Runnable action) { Platform.runLater(action); }
    static Throwable root(Throwable ex) {
        while (ex instanceof CompletionException && ex.getCause() != null) ex = ex.getCause();
        return ex;
    }
    static boolean uncertain(Throwable ex) {
        Throwable cause = root(ex);
        return cause instanceof HttpTimeoutException || cause instanceof ConnectException || cause instanceof IOException
                || (cause instanceof WalletApiClient.ApiFailure api && api.status >= 500);
    }
    static String error(Throwable ex) {
        Throwable cause = root(ex);
        if (cause instanceof WalletApiClient.ApiFailure api) return api.getMessage();
        if (cause instanceof HttpTimeoutException) return "Hết thời gian chờ server. Kiểm tra kết quả trước khi gửi yêu cầu mới.";
        if (cause instanceof IOException || cause instanceof ConnectException) return "Không kết nối được server. Kiểm tra URL và kết nối mạng.";
        return "Không thực hiện được thao tác: " + cause.getMessage();
    }
    static void status(Label label, String text, boolean error) {
        label.setText(text);
        label.getStyleClass().removeAll("status-error", "status-success");
        label.getStyleClass().add(error ? "status-error" : "status-success");
    }
    static void alert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
