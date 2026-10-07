package vn.edu.wallet.service;

/** A fault boundary used by integration tests to prove transaction rollback. */
@FunctionalInterface
public interface TransferWriteHook {
    void afterDebit();
}
