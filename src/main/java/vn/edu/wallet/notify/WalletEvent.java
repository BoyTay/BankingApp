package vn.edu.wallet.notify;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.UUID;

/** A fact that already happened to a user's account; observers decide how to tell the user. */
public sealed interface WalletEvent {
    UUID userId();
    UUID walletId();
    String type();
    String title();
    String body();

    private static String dong(long amount) {
        return NumberFormat.getInstance(Locale.of("vi", "VN")).format(amount) + " VND";
    }

    record TransferSent(UUID userId, UUID walletId, String toWalletCode, long amountDong, long balanceAfterDong)
            implements WalletEvent {
        public String type() { return "TRANSFER_SENT"; }
        public String title() { return "Bạn đã chuyển " + dong(amountDong); }
        public String body() {
            return "Đã chuyển " + dong(amountDong) + " tới ví " + toWalletCode
                    + ". Số dư còn lại: " + dong(balanceAfterDong) + ".";
        }
    }

    record TransferReceived(UUID userId, UUID walletId, String fromWalletCode, long amountDong, long balanceAfterDong)
            implements WalletEvent {
        public String type() { return "TRANSFER_RECEIVED"; }
        public String title() { return "Bạn nhận được " + dong(amountDong); }
        public String body() {
            return "Nhận " + dong(amountDong) + " từ ví " + fromWalletCode
                    + ". Số dư hiện tại: " + dong(balanceAfterDong) + ".";
        }
    }

    record GrantReceived(UUID userId, UUID walletId, long amountDong, long balanceAfterDong, String reason)
            implements WalletEvent {
        public String type() { return "GRANT_RECEIVED"; }
        public String title() { return "Quản trị viên cấp " + dong(amountDong); }
        public String body() {
            return "Được cấp " + dong(amountDong) + " (" + reason + "). Số dư hiện tại: "
                    + dong(balanceAfterDong) + ".";
        }
    }
}
