package vn.edu.wallet.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.wallet.auth.AuthInterceptor;
import vn.edu.wallet.auth.AuthService;
import vn.edu.wallet.auth.Principal;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/register")
    public ResponseEntity<ApiDtos.RegisterView> register(@RequestBody ApiDtos.RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(request));
    }

    @PostMapping("/login")
    public ApiDtos.LoginView login(@RequestBody ApiDtos.LoginRequest request) {
        return auth.login(request);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal) {
        auth.logout(principal);
        return ResponseEntity.noContent().build();
    }
}
