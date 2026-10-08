package vn.edu.wallet.account;

import org.springframework.stereotype.Component;

@Component
public class SavingsAccountPolicy implements AccountPolicy {
    @Override public String type() { return "SAVINGS"; }
    @Override public boolean allowsOutgoingTransfer() { return false; }
    @Override public boolean allowsIncomingTransfer() { return false; }
    @Override public boolean allowsAdminGrant() { return false; }
}
