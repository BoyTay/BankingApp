package vn.edu.wallet.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.wallet.auth.AuthInterceptor;
import vn.edu.wallet.auth.Principal;
import vn.edu.wallet.service.GrantService;
import vn.edu.wallet.service.ReconciliationService;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final GrantService grants;
    private final ReconciliationService reconciliation;
    public AdminController(GrantService grants, ReconciliationService reconciliation) {
        this.grants = grants;
        this.reconciliation = reconciliation;
    }

    @PostMapping("/grants")
    public ResponseEntity<ApiDtos.GrantView> grant(
            @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
            @RequestBody ApiDtos.GrantCreate request) {
        if (!"ADMIN".equals(principal.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Chỉ quản trị viên được cấp tiền");
        }
        GrantService.GrantResult result = grants.grant(principal.userId(), request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.receipt());
    }

    @GetMapping("/reconciliation")
    public ApiDtos.ReconciliationView reconciliation(
            @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        if (!"ADMIN".equals(principal.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Chỉ quản trị viên được đối soát ví");
        }
        return reconciliation.inspect(page, size);
    }
}
