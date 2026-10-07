package vn.edu.wallet.api;

import tools.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;

public final class RequestChecks {
    public static final long MAX_AMOUNT_DONG = 1_000_000_000_000L;
    private RequestChecks() {}

    public static long amount(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Số tiền phải là số nguyên VND");
        }
        long value = node.longValue();
        if (value < 1 || value > MAX_AMOUNT_DONG) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "Số tiền phải từ 1 đến 1000000000000 VND");
        }
        return value;
    }

    public static String walletCode(String code) {
        if (code == null || !code.matches("WLT[A-Fa-f0-9]{20}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Mã ví không hợp lệ");
        }
        return code.toUpperCase(java.util.Locale.ROOT);
    }
}
