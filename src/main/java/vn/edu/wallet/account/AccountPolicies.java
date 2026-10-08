package vn.edu.wallet.account;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.edu.wallet.api.ApiException;

@Component
public class AccountPolicies {
    private final Map<String, AccountPolicy> policies;

    public AccountPolicies(List<AccountPolicy> policies) {
        this.policies = policies.stream().collect(Collectors.toUnmodifiableMap(AccountPolicy::type, Function.identity()));
    }

    public AccountPolicy forType(String type) {
        AccountPolicy policy = policies.get(type);
        if (policy == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "ACCOUNT_TYPE_UNAVAILABLE", "Loại tài khoản chưa được hỗ trợ");
        return policy;
    }
}
