package vn.edu.wallet.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AccountFeeMaintenance {
    private final AccountFeeService fees;
    private final String enabled;

    public AccountFeeMaintenance(AccountFeeService fees,
            @Value("${account.fees.enabled:false}") String enabled) {
        this.fees = fees;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "UTC")
    public void runDaily() {
        if (Boolean.parseBoolean(enabled)) fees.assessAndCollect(LocalDate.now(ZoneOffset.UTC));
    }
}
