package vn.edu.wallet.api;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.edu.wallet.auth.AuthInterceptor;
import vn.edu.wallet.auth.Principal;
import vn.edu.wallet.service.CreditService;

@RestController
@RequestMapping("/api/v1/me/accounts/{id}/credit")
public class CreditController {
    private final CreditService credit;

    public CreditController(CreditService credit) { this.credit = credit; }

    @GetMapping
    public ApiDtos.CreditView get(@RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
                                  @PathVariable("id") UUID id) {
        return credit.get(principal.userId(), id);
    }

    @GetMapping("/activity")
    public List<ApiDtos.CreditActivityView> activity(
            @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal, @PathVariable("id") UUID id) {
        return credit.activity(principal.userId(), id);
    }

    @PostMapping("/charges")
    public ResponseEntity<ApiDtos.CreditSpendView> spend(
            @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal, @PathVariable("id") UUID id,
            @RequestBody ApiDtos.CreditSpend request) {
        CreditService.SpendResult result = credit.spend(principal.userId(), id, request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.view());
    }

    @PostMapping("/repayments")
    public ResponseEntity<ApiDtos.CreditRepayView> repay(
            @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal, @PathVariable("id") UUID id,
            @RequestBody ApiDtos.CreditRepay request) {
        CreditService.RepayResult result = credit.repay(principal.userId(), id, request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.view());
    }
}
