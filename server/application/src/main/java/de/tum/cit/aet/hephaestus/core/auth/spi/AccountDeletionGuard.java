package de.tum.cit.aet.hephaestus.core.auth.spi;

/** Checks domain obligations in the same transaction before an account becomes inaccessible. */
public interface AccountDeletionGuard {
    void beforeDeletion(Long accountId);
}
