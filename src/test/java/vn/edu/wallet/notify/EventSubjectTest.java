package vn.edu.wallet.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventSubjectTest {
    private final WalletEvent event = new WalletEvent.TransferReceived(UUID.randomUUID(), UUID.randomUUID(),
            "W-1", 1_000, 5_000);

    @Test
    void failingObserverDoesNotBlockTheOthers() {
        List<WalletEvent> seen = new ArrayList<>();
        EventSubject subject = new EventSubject(List.of(e -> { throw new IllegalStateException("smtp down"); },
                seen::add));
        subject.publish(event);
        assertEquals(List.of(event), seen);
    }

    @Test
    void detachedObserverStopsReceivingEvents() {
        List<WalletEvent> seen = new ArrayList<>();
        NotificationObserver observer = seen::add;
        EventSubject subject = new EventSubject(List.of());
        subject.attach(observer);
        subject.publish(event);
        subject.detach(observer);
        subject.publish(event);
        assertEquals(1, seen.size());
    }

    @Test
    void eventTextMentionsAmountAndBalance() {
        assertEquals(true, event.body().contains("1.000") && event.body().contains("5.000"));
    }
}
