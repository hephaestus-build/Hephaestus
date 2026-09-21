package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreview;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestProcessor;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles GitLab MR discussion participation into {@link PullRequestReview} rows
 * with state {@link PullRequestReview.State#COMMENTED}.
 * <p>
 * Unlike GitHub, GitLab has no first-class "review" entity. We derive one COMMENTED
 * review per {@code (author, discussion)} cluster so that leaderboard and profile
 * scoring can attribute inline feedback submissions to a review and stay at parity
 * with GitHub. Approvals are handled separately (see {@code GitLabMergeRequestProcessor}).
 * <p>
 * Idempotency: a deterministic {@code nativeId} is derived by hashing
 * {@code (discussionGlobalId, authorNativeId)}; re-running the sync reuses the same
 * row and updates {@code submittedAt} if an earlier note is discovered.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabReviewReconciler {

    private static final Logger log = LoggerFactory.getLogger(GitLabReviewReconciler.class);

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final PullRequestReviewRepository reviewRepository;
    private final ApplicationEventPublisher eventPublisher;

    public GitLabReviewReconciler(
            PullRequestReviewRepository reviewRepository, ApplicationEventPublisher eventPublisher) {
        this.reviewRepository = reviewRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Returns a {@link PullRequestReview} with state COMMENTED for the given
     * {@code (pr, author, discussion)} tuple, creating it when absent and shifting
     * {@code submittedAt} backwards when an earlier note is observed.
     *
     * @param pr the parent pull request
     * @param author the note author (must have a non-null native ID)
     * @param discussionGlobalId the GitLab Discussion GID (hex-hash string)
     * @param earliestNoteCreatedAt earliest non-system note createdAt for this author in the discussion
     * @param provider the GitLab provider
     * @param ctx processing context for event emission (may be null during webhook paths)
     * @return the reconciled review, or {@code null} when inputs are invalid
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public @Nullable PullRequestReview findOrCreateCommentedReview(
            PullRequest pr,
            User author,
            String discussionGlobalId,
            @Nullable Instant earliestNoteCreatedAt,
            IdentityProvider provider,
            @Nullable ProcessingContext ctx) {
        if (pr == null || author == null || author.getNativeId() == null || discussionGlobalId == null) {
            log.warn(
                    "Skipped COMMENTED review synthesis: prId={}, authorPresent={}, authorNativeIdPresent={}, discussionPresent={}",
                    pr != null ? pr.getId() : null,
                    author != null,
                    author != null && author.getNativeId() != null,
                    discussionGlobalId != null);
            return null;
        }

        // Parity with GitHub: a PR author replying to their own MR does not produce a
        // Review entity. Those notes are attributed via numberOfOwnReplies on the
        // leaderboard; synthesising a COMMENTED review here would inflate peer-review
        // counts (students whose only discussion activity is on their own MR would
        // appear to have reviewed peers).
        if (pr.getAuthor() != null
                && pr.getAuthor().getId() != null
                && pr.getAuthor().getId().equals(author.getId())) {
            return null;
        }

        long reviewNativeId = generateCommentedReviewNativeId(discussionGlobalId, author.getNativeId());
        Long providerId = Objects.requireNonNull(provider.getId());

        return reviewRepository
                .findByNativeIdAndProviderId(reviewNativeId, providerId)
                .map(existing -> updateReview(existing, earliestNoteCreatedAt, ctx))
                .orElseGet(() -> createReview(reviewNativeId, pr, author, provider, earliestNoteCreatedAt, ctx));
    }

    /**
     * Records when an approval was given. GitLab's {@code approvedBy} carries no time, so the approval
     * review is created at the merge request's merged or updated time; the system note "approved this
     * merge request" is the moment the approver acted, and it is the moment a practice about approving
     * before merging needs. The earliest such note by the approver stands; an approval later than the
     * note is moved back, one earlier is left alone.
     *
     * @return the review as it stands after the note, or {@code null} when no approval by this user is
     *     recorded for the merge request
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public @Nullable PullRequestReview recordApprovalTime(
            PullRequest pr, User approver, Instant approvedAt, IdentityProvider provider) {
        if (pr.getNativeId() == null || approver.getNativeId() == null || provider.getId() == null) {
            return null;
        }
        long approvalNativeId =
                GitLabMergeRequestProcessor.generateApprovalNativeId(pr.getNativeId(), approver.getNativeId());
        PullRequestReview review = reviewRepository
                .findByNativeIdAndProviderId(approvalNativeId, provider.getId())
                .orElse(null);
        if (review == null || review.getState() != PullRequestReview.State.APPROVED) {
            return review;
        }
        Instant recorded = review.getSubmittedAt();
        if (recorded == null || approvedAt.isBefore(recorded)) {
            review.setSubmittedAt(approvedAt);
            review.setCreatedAt(approvedAt);
            review.setUpdatedAt(Instant.now());
            return reviewRepository.save(review);
        }
        return review;
    }

    private PullRequestReview updateReview(
            PullRequestReview existing, @Nullable Instant earliestNoteCreatedAt, @Nullable ProcessingContext ctx) {
        if (earliestNoteCreatedAt != null) {
            Instant currentSubmittedAt = existing.getSubmittedAt();
            if (currentSubmittedAt == null || earliestNoteCreatedAt.isBefore(currentSubmittedAt)) {
                existing.setSubmittedAt(earliestNoteCreatedAt);
                existing.setUpdatedAt(Instant.now());
                reviewRepository.save(existing);
            }
        }

        // Re-publish ReviewSubmitted during re-sync so that COMMENTED reviews synced
        // before the event emission was fixed still get an activity_event row. The
        // activity_event unique constraint on (workspace_id, event_key) dedupes, so
        // replaying is safe.
        if (ctx != null) {
            ScmEventPayload.ReviewData.from(existing)
                    .ifPresent(reviewData -> eventPublisher.publishEvent(
                            new ScmDomainEvent.ReviewSubmitted(reviewData, EventContext.from(ctx))));
        }
        return existing;
    }

    private PullRequestReview createReview(
            long reviewNativeId,
            PullRequest pr,
            User author,
            IdentityProvider provider,
            @Nullable Instant earliestNoteCreatedAt,
            @Nullable ProcessingContext ctx) {
        Instant submittedAt = earliestNoteCreatedAt != null
                ? earliestNoteCreatedAt
                : (pr.getUpdatedAt() != null ? pr.getUpdatedAt() : Instant.now());

        PullRequestReview review = new PullRequestReview();
        review.setNativeId(reviewNativeId);
        review.setProvider(provider);
        review.setState(PullRequestReview.State.COMMENTED);
        review.setHtmlUrl(pr.getHtmlUrl() != null ? pr.getHtmlUrl() + "#discussion" : "");
        review.setSubmittedAt(submittedAt);
        review.setCreatedAt(submittedAt);
        review.setUpdatedAt(Instant.now());
        review.setAuthor(author);
        review.setPullRequest(pr);

        PullRequestReview saved = reviewRepository.save(review);
        pr.addReview(saved);

        if (ctx != null) {
            ScmEventPayload.ReviewData.from(saved)
                    .ifPresent(reviewData -> eventPublisher.publishEvent(
                            new ScmDomainEvent.ReviewSubmitted(reviewData, EventContext.from(ctx))));
        }

        log.debug(
                "Created COMMENTED review from discussion: prId={}, author={}, nativeId={}",
                pr.getId(),
                author.getLogin(),
                reviewNativeId);
        return saved;
    }

    /**
     * Produces a deterministic positive Long native ID for a COMMENTED review.
     * <p>
     * Uses FNV-1a over {@code discussionGlobalId + "|" + authorNativeId}. Collisions
     * with the bit-packed approval native IDs ({@code (mr<<32)|user}) are
     * astronomically unlikely because the two schemes occupy different hash spaces;
     * any collision would surface as a DB unique-constraint violation and is logged.
     */
    public static long generateCommentedReviewNativeId(String discussionGlobalId, long authorNativeId) {
        long hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < discussionGlobalId.length(); i++) {
            hash ^= discussionGlobalId.charAt(i);
            hash *= FNV_PRIME;
        }
        hash ^= '|';
        hash *= FNV_PRIME;
        long mixed = authorNativeId;
        for (int i = 0; i < 8; i++) {
            hash ^= (mixed & 0xFFL);
            hash *= FNV_PRIME;
            mixed >>>= 8;
        }
        return hash & Long.MAX_VALUE;
    }
}
