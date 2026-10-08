package vn.edu.wallet.core;

import java.util.Locale;
import java.util.UUID;

public final class WalletCodes {
    private WalletCodes() {}

    public static String fromId(UUID id) {
        return "WLT" + id.toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
    }
}
