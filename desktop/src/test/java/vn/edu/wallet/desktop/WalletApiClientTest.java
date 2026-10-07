package vn.edu.wallet.desktop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WalletApiClientTest {
    @Test void acceptsLocalHttpAndRemoteHttps() {
        assertEquals("http://localhost:8080/api/v1", WalletApiClient.checkedBase(" http://localhost:8080/api/v1/ ").toString());
        assertEquals("http://127.0.0.1:18080/api/v1", WalletApiClient.checkedBase("http://127.0.0.1:18080/api/v1").toString());
        assertEquals("https://wallet.example.test/api/v1", WalletApiClient.checkedBase("https://wallet.example.test/api/v1").toString());
    }

    @Test void rejectsRemoteHttpAndMalformedUrls() {
        for (String url : new String[] {
                "http://wallet.example.test/api/v1", "http://localhost.example.test/api/v1",
                "http://127.0.0.2/api/v1", "http://[::1]/api/v1", "http://user@localhost/api/v1",
                "https://user:pass@wallet.example.test/api/v1", "https://wallet.example.test/api/v1?token=x",
                "https://wallet.example.test/api/v1#fragment", "ftp://wallet.example.test/api/v1",
                "localhost:8080/api/v1", "not a url", "" }) {
            assertThrows(IllegalArgumentException.class, () -> WalletApiClient.checkedBase(url), url);
        }
    }
}
