package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.time.Instant;

/**
 * Ends credentials that a module keeps outside the account's sessions when every session of the account ends: sign
 * out everywhere, a block, an admin revoke or an account deletion. It runs in that revocation's transaction, after
 * the account's row lock, so no issuance that waits for the lock can outlive it.
 */
public interface AccountSessionRevocationContributor {
    void revokeAll(long accountId, Instant at);
}
