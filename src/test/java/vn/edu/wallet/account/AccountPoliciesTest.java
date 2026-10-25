package vn.edu.wallet.account;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import vn.edu.wallet.api.ApiException;

class AccountPoliciesTest {
    private final AccountPolicies policies = new AccountPolicies(List.of(
            new CheckingAccountPolicy(), new SavingsAccountPolicy(), new CreditAccountPolicy()));

    @Test void checkingAllowsEverything() {
        AccountPolicy policy = policies.forType("CHECKING");
        assertDoesNotThrow(policy::requireOutgoing);
        assertDoesNotThrow(policy::requireIncoming);
        assertDoesNotThrow(policy::requireAdminGrant);
    }

    @Test void savingsAndCreditRejectDirectMoneyMovement() {
        for (String type : List.of("SAVINGS", "CREDIT")) {
            AccountPolicy policy = policies.forType(type);
            for (Runnable check : List.<Runnable>of(policy::requireOutgoing, policy::requireIncoming,
                    policy::requireAdminGrant)) {
                ApiException error = assertThrows(ApiException.class, check::run, type);
                assertEquals("ACCOUNT_OPERATION_NOT_ALLOWED", error.code());
                assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.status());
            }
        }
    }

    @Test void unknownTypeIsUnavailable() {
        for (String type : new String[] {"GOLD", "checking", ""}) {
            ApiException error = assertThrows(ApiException.class, () -> policies.forType(type));
            assertEquals("ACCOUNT_TYPE_UNAVAILABLE", error.code());
        }
    }

    @Test void eachPolicyReportsItsOwnType() {
        assertEquals("CHECKING", new CheckingAccountPolicy().type());
        assertEquals("SAVINGS", new SavingsAccountPolicy().type());
        assertEquals("CREDIT", new CreditAccountPolicy().type());
    }
}
