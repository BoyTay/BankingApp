package vn.edu.wallet.notify;

/** Observer: reacts to wallet events published by {@link EventSubject}. */
public interface NotificationObserver {
    void update(WalletEvent event);
}
