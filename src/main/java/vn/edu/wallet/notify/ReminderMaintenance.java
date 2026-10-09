package vn.edu.wallet.notify;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReminderMaintenance {
    private final ReminderService reminders;
    private final boolean enabled;

    public ReminderMaintenance(ReminderService reminders,
            @Value("${notify.reminders.enabled:false}") boolean enabled) {
        this.reminders = reminders;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 15 0 * * *", zone = "UTC")
    public void runDaily() {
        if (enabled) reminders.run(LocalDate.now(ZoneOffset.UTC));
    }
}
