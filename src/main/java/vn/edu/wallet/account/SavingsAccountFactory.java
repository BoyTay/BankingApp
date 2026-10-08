package vn.edu.wallet.account;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SavingsAccountFactory extends AccountFactory {
    public SavingsAccountFactory() { super(Clock.systemUTC()); }

    @Override public String type() { return "SAVINGS"; }

    @Override
    protected AccountOpening build(UUID id, UUID ownerId, String code,
                                   UUID requestKey, LocalDate feeStartsOn) {
        return new AccountOpening(id, ownerId, code, type(), requestKey, feeStartsOn);
    }
}
