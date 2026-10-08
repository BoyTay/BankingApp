package vn.edu.wallet.account;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import vn.edu.wallet.core.WalletCodes;

public abstract class AccountFactory {
    private final Clock clock;

    protected AccountFactory(Clock clock) { this.clock = clock; }

    public abstract String type();

    public final AccountOpening create(UUID ownerId, UUID requestKey) {
        UUID id = UUID.randomUUID();
        LocalDate feeStartsOn = LocalDate.now(clock.withZone(ZoneOffset.UTC))
                .withDayOfMonth(1).plusMonths(1);
        return build(id, ownerId, WalletCodes.fromId(id), requestKey, feeStartsOn);
    }

    protected abstract AccountOpening build(UUID id, UUID ownerId, String code,
                                            UUID requestKey, LocalDate feeStartsOn);
}
