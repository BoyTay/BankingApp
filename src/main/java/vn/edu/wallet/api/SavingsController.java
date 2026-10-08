package vn.edu.wallet.api;

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
import vn.edu.wallet.service.SavingsService;

@RestController
@RequestMapping("/api/v1/me/accounts/{id}/savings")
public class SavingsController {
    private final SavingsService savings;

    public SavingsController(SavingsService savings) { this.savings = savings; }

    @GetMapping
    public ApiDtos.SavingsView get(@RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
                                   @PathVariable("id") UUID accountId) {
        return savings.get(principal.userId(), accountId);
    }

    @PostMapping("/withdraw")
    public ResponseEntity<ApiDtos.SavingsWithdrawalView> withdraw(
            @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
            @PathVariable("id") UUID accountId,
            @RequestBody ApiDtos.SavingsWithdraw request) {
        SavingsService.WithdrawalResult result = savings.withdraw(principal.userId(), accountId, request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result.withdrawal());
    }
}
