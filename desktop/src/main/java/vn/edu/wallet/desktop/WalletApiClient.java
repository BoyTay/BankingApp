package vn.edu.wallet.desktop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Network only. Raw session token is held in memory and never logged or persisted. */
public final class WalletApiClient {
    private final URI base;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private volatile String token;

    public WalletApiClient(String baseUrl) {
        base = checkedBase(baseUrl);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    static URI checkedBase(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        URI candidate;
        try { candidate = URI.create(normalized); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("URL server không hợp lệ", ex); }
        String scheme = candidate.getScheme();
        String host = candidate.getHost();
        if (scheme == null || host == null || candidate.getRawUserInfo() != null
                || candidate.getRawQuery() != null || candidate.getRawFragment() != null
                || (candidate.getPort() < -1 || candidate.getPort() > 65535)) {
            throw new IllegalArgumentException("URL server không hợp lệ");
        }
        boolean local = host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1");
        if (!(scheme.equalsIgnoreCase("https") || (scheme.equalsIgnoreCase("http") && local))) {
            throw new IllegalArgumentException("HTTP chỉ dùng với localhost hoặc 127.0.0.1; server khác phải dùng HTTPS");
        }
        return candidate;
    }

    public String baseUrl() { return base.toString(); }
    public void useToken(String value) { token = value; }
    public void clearToken() { token = null; }
    public JsonNode object(Object value) { return json.valueToTree(value); }

    public CompletableFuture<JsonNode> get(String path) { return send("GET", path, null); }
    public CompletableFuture<JsonNode> post(String path, JsonNode body) { return send("POST", path, body); }

    private CompletableFuture<JsonNode> send(String method, String path, JsonNode body) {
        HttpRequest.Builder builder = request(path).header("Accept", "application/json");
        if (body != null) builder.header("Content-Type", "application/json; charset=UTF-8");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    check(response.statusCode(), response.body());
                    return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
                });
    }

    public CompletableFuture<byte[]> download(String path) {
        return http.sendAsync(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() >= 400) check(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
                    return response.body();
                });
    }

    public CompletableFuture<JsonNode> upload(String format, UUID requestKey, Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > 1_048_576) throw new ApiFailure(413, "FILE_TOO_LARGE", "Tệp CSV vượt quá 1 MiB");
            String boundary = "wallet-" + UUID.randomUUID();
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            stream.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"requestKey\"\r\n\r\n"
                    + requestKey + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"expenses.csv\"\r\n"
                    + "Content-Type: text/csv; charset=UTF-8\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            stream.write(bytes);
            stream.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest request = request("/expense-imports?format=" + encode(format))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(stream.toByteArray())).build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> {
                        check(response.statusCode(), response.body());
                        return json.readTree(response.body());
                    });
        } catch (IOException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(base.getPath().replaceAll("/+$", "") + path))
                .timeout(Duration.ofSeconds(20));
        String current = token;
        if (current != null) builder.header("Authorization", "Bearer " + current);
        return builder;
    }

    private void check(int status, String body) {
        if (status < 400) return;
        try {
            JsonNode error = json.readTree(body);
            throw new ApiFailure(status, error.path("code").asString("HTTP_" + status),
                    error.path("message").asString("Yêu cầu thất bại"));
        } catch (ApiFailure ex) { throw ex; }
        catch (RuntimeException ex) { throw new ApiFailure(status, "HTTP_" + status, "Yêu cầu thất bại (HTTP " + status + ")"); }
    }

    public static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public static final class ApiFailure extends RuntimeException {
        public final int status;
        public final String code;
        ApiFailure(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }
}
