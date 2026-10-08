package vn.edu.wallet.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.edu.wallet.auth.AuthInterceptor;
import vn.edu.wallet.auth.Principal;
import vn.edu.wallet.service.CreditService;

@RestController
@RequestMapping("/api/v1/admin/credit-accounts")
public class CreditAdminController {
    private final CreditService credit;

    public CreditAdminController(CreditService credit) { this.credit = credit; }

    @PostMapping("/{id}/limit")
    public ResponseEntity<ApiDtos.CreditLimitView> setLimit(
            @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
            @PathVariable("id") UUID id, @RequestBody ApiDtos.CreditLimitSet request) {
        if (!"ADMIN".equals(principal.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Chỉ quản trị viên được cấp hạn mức");
        }
        CreditService.LimitResult result = credit.setLimit(principal.userId(), id, request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.view());
    }
}
