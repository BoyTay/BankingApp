package vn.edu.wallet.account;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CreditAccountFactory extends AccountFactory {
    public CreditAccountFactory() { super(Clock.systemUTC()); }

    @Override public String type() { return "CREDIT"; }

    @Override
    protected AccountOpening build(UUID id, UUID ownerId, String code,
                                   UUID requestKey, LocalDate feeStartsOn) {
        return new AccountOpening(id, ownerId, code, type(), requestKey, feeStartsOn);
    }
}
