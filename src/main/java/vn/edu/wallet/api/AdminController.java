package vn.edu.wallet.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.wallet.auth.AuthInterceptor;
import vn.edu.wallet.auth.Principal;
import vn.edu.wallet.service.GrantService;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final GrantService grants;
    public AdminController(GrantService grants) { this.grants = grants; }

    @PostMapping("/grants")
    public ResponseEntity<ApiDtos.GrantView> grant(
            @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
            @RequestBody ApiDtos.GrantCreate request) {
        if (!"ADMIN".equals(principal.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Chỉ quản trị viên được cấp tiền");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(grants.grant(principal.userId(), request));
    }
}
