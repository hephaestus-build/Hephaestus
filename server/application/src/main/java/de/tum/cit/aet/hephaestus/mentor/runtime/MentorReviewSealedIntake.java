package de.tum.cit.aet.hephaestus.mentor.runtime;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorReviewIntake;
import java.nio.charset.StandardCharsets;
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
        // The fence share-locks the account row, so these values hold until the seal commits.
        var view = accounts.find(account).orElse(null);
        if (view == null || view.status() != Account.Status.ACTIVE)
            return new Outcome.Refused(Refusal.AUTHOR_INACTIVE);
        if (!view.feedbackDeliveryEnabled() || view.aiChoice() == null || view.aiChoice() == AiChoice.NO_AI)
            return new Outcome.Refused(Refusal.AUTHOR_NOT_CONSENTING);
        if (!memberships.holdActiveVisibleMember(sealed.workspaceId(), account))
            return new Outcome.Refused(Refusal.AUTHOR_NOT_MEMBER);
        owners.ofJob(account);
        UUID id = id(sealed.workspaceId(), sealed.reviewId());
        if (events.insertReviewSealed(
                        id,
                        account,
                        sealed.workspaceId(),
                        sealed.reviewId(),
                        sealed.jobId(),
                        sealed.admissionDigest(),
                        sealed.sealedAt(),
                        Instant.now())
                == 1) return new Outcome.Admitted(id);
        // The review has an event. Only the same owner, job and admission is this seal again; row-level security hides
        // another owner's event, so that is a mismatch too.
        return events.findReviewSealed(
                        sealed.workspaceId(),
                        sealed.reviewId(),
                        account,
                        sealed.jobId(),
                        sealed.admissionDigest())
                .<Outcome>map(existing -> new Outcome.Admitted(UUID.fromString(existing)))
                .orElse(new Outcome.Refused(Refusal.EVENT_MISMATCH));
    }

    /** The review names its event, so every seal of it names the same row. */
    static UUID id(long workspaceId, UUID reviewId) {
        return UUID.nameUUIDFromBytes(("review-sealed:" + workspaceId + ":" + reviewId).getBytes(StandardCharsets.UTF_8));
    }
}
