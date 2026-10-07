package vn.edu.wallet.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String PRINCIPAL_ATTRIBUTE = "walletPrincipal";
    private final AuthService auth;

    public AuthInterceptor(AuthService auth) { this.auth = auth; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        if (path.equals("/api/v1/auth/register") || path.equals("/api/v1/auth/login")) return true;
        request.setAttribute(PRINCIPAL_ATTRIBUTE, auth.authenticate(request.getHeader("Authorization")));
        return true;
    }
}
