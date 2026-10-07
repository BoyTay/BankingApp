package vn.edu.wallet.desktop;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class WalletDesktopApp extends Application {
    private Stage stage;
    private WalletApiClient api = new WalletApiClient(
            System.getProperty("wallet.api.url", System.getenv().getOrDefault("WALLET_API_URL", "http://localhost:8080/api/v1")));

    @Override public void start(Stage primaryStage) throws Exception {
        stage = primaryStage;
        stage.setTitle("Ví Nội Bộ · BankingApp");
        stage.setMinWidth(1040);
        stage.setMinHeight(700);
        showLogin();
        stage.show();
    }

    void showLogin() throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/login.fxml"));
        Parent root = loader.load();
        loader.<LoginController>getController().init(this, api);
        setScene(root);
    }

    void showWorkspace(String name, String role) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/workspace.fxml"));
        Parent root = loader.load();
        loader.<WorkspaceController>getController().init(this, api, name, role);
        setScene(root);
    }

    void useClient(WalletApiClient client) { api = client; }

    private void setScene(Parent root) {
        Scene scene = new Scene(root, 1180, 780);
        scene.getStylesheets().add(getClass().getResource("/css/wallet.css").toExternalForm());
        stage.setScene(scene);
    }

    public static void main(String[] args) { launch(args); }
}
