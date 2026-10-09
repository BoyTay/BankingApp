package vn.edu.wallet.notify;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Observer pattern Subject. Services call {@link #publish} inside their transaction; observers run only
 * after commit so a rolled-back transfer never notifies anyone, and one failing observer never breaks
 * the money movement or the other observers.
 */
@Component
public class EventSubject {
    private static final Logger log = LoggerFactory.getLogger(EventSubject.class);
    private final List<NotificationObserver> observers = new CopyOnWriteArrayList<>();

    public EventSubject(List<NotificationObserver> registered) {
        registered.forEach(this::attach);
    }

    public void attach(NotificationObserver observer) { observers.add(observer); }

    public void detach(NotificationObserver observer) { observers.remove(observer); }

    public void publish(WalletEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { deliver(event); }
            });
        } else {
            deliver(event);
        }
    }

    /** Notifies observers immediately; use when the caller is already past the commit. */
    public void deliver(WalletEvent event) {
        for (NotificationObserver observer : observers) {
            try {
                observer.update(event);
            } catch (RuntimeException ex) {
                log.warn("Observer {} failed for {}", observer.getClass().getSimpleName(), event.type(), ex);
            }
        }
    }
}
