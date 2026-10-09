package vn.edu.wallet.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.edu.wallet.auth.AuthInterceptor;
import vn.edu.wallet.auth.Principal;
import vn.edu.wallet.notify.NotificationService;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) { this.notifications = notifications; }

    @GetMapping("/notifications")
    public NotificationService.NotificationPage list(
            @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return notifications.list(principal.userId(), page, size);
    }

    @PostMapping("/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
                     @PathVariable("id") UUID id) {
        notifications.markRead(principal.userId(), id);
    }

    @PostMapping("/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll(@RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal) {
        notifications.markAllRead(principal.userId());
    }

    @GetMapping("/me/accounts/{id}/notification-settings")
    public NotificationService.SettingsView settings(
            @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
            @PathVariable("id") UUID id) {
        return notifications.settings(principal.userId(), id);
    }

    @PutMapping("/me/accounts/{id}/notification-settings")
    public NotificationService.SettingsView updateSettings(
            @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) Principal principal,
            @PathVariable("id") UUID id, @RequestBody JsonNode body) {
        return notifications.updateSettings(principal.userId(), id, body == null ? null : body.get("lowBalanceDong"));
    }
}
