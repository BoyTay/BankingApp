package vn.edu.wallet.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Simulated SMS channel: the wallet stores no phone numbers, so it only logs what would be sent. */
@Component
public class SmsObserver implements NotificationObserver {
    private static final Logger log = LoggerFactory.getLogger(SmsObserver.class);

    @Override
    public void update(WalletEvent event) {
        log.info("SMS (simulated) user={} type={}", event.userId(), event.type());
    }
}
