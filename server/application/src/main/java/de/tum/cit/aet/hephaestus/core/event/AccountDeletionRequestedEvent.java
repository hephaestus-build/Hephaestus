package de.tum.cit.aet.hephaestus.core.event;

/**
 * A person deleted their own account: {@code core.auth.AccountService#softDelete} committed the {@code DELETING}
 * status and revoked every session. {@code core.auth.AccountHardDeleteSweeper} purges the account once the deleting
 * transaction commits.
 *
 * <p>Published inside the deleting transaction, so the purge runs only after a committed deletion. Carries the
 * account id only, because the registry serialises every event into {@code event_publication}.
 *
 * @param accountId the account to purge
 */
public record AccountDeletionRequestedEvent(long accountId) {}
