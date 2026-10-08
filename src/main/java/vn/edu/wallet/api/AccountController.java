package vn.edu.wallet.api;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.wallet.auth.AuthInterceptor;
import vn.edu.wallet.auth.Principal;
import vn.edu.wallet.service.AccountService;
import vn.edu.wallet.service.AccountFeeService;

@RestController
@RequestMapping("/api/v1/me/accounts")
public class AccountController {
    private final AccountService accounts;
    private final AccountFeeService fees;

    public AccountController(AccountService accounts, AccountFeeService fees) {
        this.accounts = accounts;
        this.fees = fees;
    }

    @GetMapping
    public List<ApiDtos.AccountView> list(@RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal) {
        return accounts.list(principal.userId());
    }

    @GetMapping("/{id}")
    public ApiDtos.AccountView get(@RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
                                   @PathVariable("id") UUID id) {
        return accounts.get(principal.userId(), id);
    }

    @GetMapping("/{id}/fees")
    public List<ApiDtos.AccountFeeView> fees(@RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
                                             @PathVariable("id") UUID id) {
        return fees.list(principal.userId(), id);
    }

    @PostMapping
    public ResponseEntity<ApiDtos.AccountView> open(
            @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
            @RequestBody ApiDtos.AccountCreate request) {
        AccountService.OpenResult result = accounts.open(principal.userId(), request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.account());
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<ApiDtos.AccountCloseView> close(
            @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
            @PathVariable("id") UUID id, @RequestBody ApiDtos.AccountClose request) {
        AccountService.CloseResult result = accounts.close(principal.userId(), id, request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.view());
    }
}
