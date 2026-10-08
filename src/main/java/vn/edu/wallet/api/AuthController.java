package vn.edu.wallet.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.wallet.auth.AuthInterceptor;
import vn.edu.wallet.auth.AuthService;
import vn.edu.wallet.auth.AuthRateLimiter;
import vn.edu.wallet.auth.Principal;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final AuthRateLimiter rateLimiter;
    public AuthController(AuthService auth, AuthRateLimiter rateLimiter) {
        this.auth = auth;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiDtos.RegisterView> register(@RequestBody ApiDtos.RegisterRequest request,
                                                         HttpServletRequest servletRequest) {
        rateLimiter.registrationAttempt(servletRequest.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(request));
    }

    @PostMapping("/login")
    public ApiDtos.LoginView login(@RequestBody ApiDtos.LoginRequest request,
                                   HttpServletRequest servletRequest) {
        String address = servletRequest.getRemoteAddr();
        rateLimiter.loginAttempt(address, request == null ? null : request.email());
        ApiDtos.LoginView result = auth.login(request);
        rateLimiter.loginSucceeded(address, request.email());
        return result;
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal) {
        auth.logout(principal);
        return ResponseEntity.noContent().build();
    }
}
