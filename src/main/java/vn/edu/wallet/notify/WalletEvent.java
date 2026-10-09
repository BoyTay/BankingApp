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

    record LowBalance(UUID userId, UUID walletId, String walletCode, long balanceDong, long thresholdDong)
            implements WalletEvent {
        public String type() { return "LOW_BALANCE"; }
        public String title() { return "Số dư thấp: " + dong(balanceDong); }
        public String body() {
            return "Số dư ví " + walletCode + " là " + dong(balanceDong) + ", thấp hơn ngưỡng cảnh báo "
                    + dong(thresholdDong) + ".";
        }
    }

    record FeeDue(UUID userId, UUID walletId, String walletCode, long amountDong, String periodStart)
            implements WalletEvent {
        public String type() { return "FEE_DUE"; }
        public String title() { return "Phí " + dong(amountDong) + " chưa thu được"; }
        public String body() {
            return "Phí kỳ " + periodStart + " của ví " + walletCode + " (" + dong(amountDong)
                    + ") chưa thu được do không đủ số dư. Hãy nạp thêm tiền để hệ thống thu phí.";
        }
    }

    record CreditDebt(UUID userId, UUID walletId, String walletCode, long debtDong) implements WalletEvent {
        public String type() { return "CREDIT_DEBT"; }
        public String title() { return "Nhắc trả nợ tín dụng " + dong(debtDong); }
        public String body() {
            return "Tài khoản tín dụng " + walletCode + " đang dư nợ " + dong(debtDong)
                    + ". Hãy trả nợ từ tài khoản thanh toán của bạn.";
        }
    }

    record SavingsMaturing(UUID userId, UUID walletId, String walletCode, String maturesOn, long daysLeft)
            implements WalletEvent {
        public String type() { return "SAVINGS_MATURING"; }
        public String title() { return "Tiết kiệm sắp đáo hạn"; }
        public String body() {
            return "Khoản tiết kiệm " + walletCode + " đáo hạn ngày " + maturesOn + " (còn " + daysLeft
                    + " ngày). Rút trước hạn sẽ bị tính phí.";
        }
    }
}
