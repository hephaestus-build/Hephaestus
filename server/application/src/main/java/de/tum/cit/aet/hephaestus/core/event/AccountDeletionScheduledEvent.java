package de.tum.cit.aet.hephaestus.core.event;

import java.time.Instant;

/**
 * An account entered its GDPR Art. 17 cooldown: {@code core.auth.AccountService#softDelete} committed
 * the {@code DELETING} status and the purge will run once {@code purgeAfter} has passed.
 *
 * <p>Published inside the deleting transaction, so a {@code @ApplicationModuleListener} in the
 * {@code notification} module gets a durable registry row in the same commit (ADR 0044) and tells
 * the person by email. Carries the account id only: the registry serialises every event into
 * {@code event_publication}, and the address is resolved at delivery time through
 * {@code core.auth.spi.AccountContactQuery}, which still answers during the cooldown.
 *
 * @param accountId  the account whose purge is scheduled
 * @param purgeAfter the earliest instant the hard-delete sweeper erases the account
 */
public record AccountDeletionScheduledEvent(long accountId, Instant purgeAfter) {}
