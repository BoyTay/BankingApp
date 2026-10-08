package vn.edu.wallet.account;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CheckingAccountFactory extends AccountFactory {
    public CheckingAccountFactory() { super(Clock.systemUTC()); }

    @Override public String type() { return "CHECKING"; }

    @Override
    protected AccountOpening build(UUID id, UUID ownerId, String code,
                                   UUID requestKey, LocalDate feeStartsOn) {
        return new AccountOpening(id, ownerId, code, type(), requestKey, feeStartsOn);
    }
}
