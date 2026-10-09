package vn.edu.wallet.notify;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Sends an email over SMTP (Mailpit in Docker) off the caller's thread. Disabled unless notify.email.enabled. */
@Component
public class EmailObserver implements NotificationObserver {
    private static final Logger log = LoggerFactory.getLogger(EmailObserver.class);
    private final JdbcTemplate db;
    private final ObjectProvider<JavaMailSender> mail;
    private final boolean enabled;
    private final String from;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public EmailObserver(JdbcTemplate db, ObjectProvider<JavaMailSender> mail,
            @Value("${notify.email.enabled:false}") boolean enabled,
            @Value("${notify.email.from:wallet@internal-wallet.local}") String from) {
        this.db = db;
        this.mail = mail;
        this.enabled = enabled;
        this.from = from;
    }

    @Override
    public void update(WalletEvent event) {
        JavaMailSender sender = enabled ? mail.getIfAvailable() : null;
        if (sender == null) return;
        executor.execute(() -> {
            try {
                List<String> emails = db.queryForList("SELECT email FROM app_users WHERE id=?",
                        String.class, event.userId());
                if (emails.isEmpty()) return;
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(emails.getFirst());
                message.setSubject("[Ví nội bộ] " + event.title());
                message.setText(event.body());
                sender.send(message);
            } catch (RuntimeException ex) {
                log.warn("Email notification {} failed: {}", event.type(), ex.toString());
            }
        });
    }

    @PreDestroy
    void shutdown() { executor.close(); }
}
