package de.tum.cit.aet.hephaestus.mentor.runtime;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorReviewIntake;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Binds a sealed review's mailbox event to its reviewed account (ADR 0160). The seal holds the copy fence shared, the job
 * and the review. Then this takes the account's write fence, rereads the account under it, and holds the membership
 * row, in that order, so a leave, a hide, a status change or a preference change commits after the seal or refuses it.
 * The seal named the account, so its owner is stored, not a principal.
 */
@Component
@ConditionalOnServerRole
@OwnerScope.StoredOwnerOperation
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MentorReviewSealedIntake implements MentorReviewIntake {
    private final PersonDataWriteFence fence;
    private final AccountDirectory accounts;
    private final AccountMembershipQuery memberships;
    private final OwnerScope owners;
    private final MentorEventRepository events;

    @Override
    public Outcome admit(SealedReview sealed) {
        long account = sealed.accountId();
        if (!fence.holdForAccountWrite(account)) return new Outcome.Refused(Refusal.OWNER_REFUSED);
        // The fence share-locks the account row, so the committed values hold until the seal commits. A cached account
        // that the seal loaded earlier could predate a withdrawn choice, so this reads the row itself.
        var consent = accounts.committedConsent(account).orElse(null);
        if (consent == null || consent.status() != Account.Status.ACTIVE)
            return new Outcome.Refused(Refusal.AUTHOR_INACTIVE);
        if (!consent.feedbackDeliveryEnabled() || consent.aiChoice() == null || consent.aiChoice() == AiChoice.NO_AI)
            return new Outcome.Refused(Refusal.AUTHOR_NOT_CONSENTING);
        if (!memberships.holdActiveVisibleMember(sealed.workspaceId(), account))
            return new Outcome.Refused(Refusal.AUTHOR_NOT_MEMBER);
        owners.ofJob(account);
        // A random id keeps the review key the only unique key that two seals of one review can meet, so the
        // targeted conflict absorbs every race between them, and a provider event's keys are never absorbed.
        UUID id = UUID.randomUUID();
        if (events.insertReviewSealed(id, sealed, Instant.now()) == 1) return new Outcome.Admitted(id);
        // The review has an event. Only the same owner, job and admission is this seal again; row-level security hides
        // another owner's event, so that is a mismatch too.
        return events.findReviewSealed(sealed)
                .<Outcome>map(existing -> new Outcome.Admitted(UUID.fromString(existing)))
                .orElse(new Outcome.Refused(Refusal.EVENT_MISMATCH));
    }
}
