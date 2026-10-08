package vn.edu.wallet.account;

import org.springframework.http.HttpStatus;
import vn.edu.wallet.api.ApiException;

public interface AccountPolicy {
    String type();
    boolean allowsOutgoingTransfer();
    boolean allowsIncomingTransfer();
    boolean allowsAdminGrant();

    default void requireOutgoing() {
        if (!allowsOutgoingTransfer()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "ACCOUNT_OPERATION_NOT_ALLOWED", "Loại tài khoản này không được chuyển tiền");
    }

    default void requireIncoming() {
        if (!allowsIncomingTransfer()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "ACCOUNT_OPERATION_NOT_ALLOWED", "Loại tài khoản này không nhận chuyển tiền trực tiếp");
    }

    default void requireAdminGrant() {
        if (!allowsAdminGrant()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "ACCOUNT_OPERATION_NOT_ALLOWED", "Loại tài khoản này không nhận cấp tiền trực tiếp");
    }
}
