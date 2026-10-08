package vn.edu.wallet.account;

import org.springframework.stereotype.Component;

@Component
public class CheckingAccountPolicy implements AccountPolicy {
    @Override public String type() { return "CHECKING"; }
    @Override public boolean allowsOutgoingTransfer() { return true; }
    @Override public boolean allowsIncomingTransfer() { return true; }
    @Override public boolean allowsAdminGrant() { return true; }
}
