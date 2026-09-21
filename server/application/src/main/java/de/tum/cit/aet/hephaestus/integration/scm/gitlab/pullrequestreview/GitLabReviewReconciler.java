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
import java.util.Set;
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
     * GitLab's own wording for the system notes it writes on a review decision, as the notes carry it;
     * the only place either the sync or the note webhook learns who decided what, and when.
     */
    public static final String APPROVED_SYSTEM_NOTE = "approved this merge request";

    public static final String UNAPPROVED_SYSTEM_NOTE = "unapproved this merge request";
    public static final String REQUESTED_CHANGES_SYSTEM_NOTE = "requested changes";

    /**
     * Records one review-decision system note, from the discussion sync or the note webhook.
     *
     * <p>A live approval note is the approval itself, so it may create the approval row and give a
     * dismissed one again; a replayed one only re-times what {@code approvedBy} already established,
     * and re-approves only after an unapproval note the same pass has seen.
     *
     * @param unapproved the authors whose unapproval note this pass has already seen; added to here
     * @return whether the body was a review-decision note at all
     */
    /**
     * One review-decision system note as GitLab wrote it.
     *
     * @param body the note's body
     * @param at when the note was written
     * @param globalId the note's GID ({@code gid://gitlab/Note/<id>}), the key of a CHANGES_REQUESTED row
     * @param live whether the note has just been written (a webhook), as opposed to a note replayed by
     *     the sync against the current record
     */
    public record SystemNote(String body, Instant at, String globalId, boolean live) {}

    @Transactional(propagation = Propagation.REQUIRED)
    public boolean recordSystemNote(
            PullRequest pr, User author, SystemNote note, IdentityProvider provider, Set<Long> unapproved) {
        Long authorNativeId = author.getNativeId();
        if (authorNativeId == null) {
            return false;
        }
        switch (note.body().strip()) {
            case APPROVED_SYSTEM_NOTE -> {
                PullRequestReview review = recordApprovalTime(
                        pr, author, note.at(), provider, note.live() || unapproved.contains(authorNativeId));
                if (review == null && note.live()) {
                    createLiveApproval(pr, author, note.at(), provider);
                }
            }
            case UNAPPROVED_SYSTEM_NOTE -> {
                recordUnapproval(pr, author, note.at(), provider);
                unapproved.add(authorNativeId);
            }
            case REQUESTED_CHANGES_SYSTEM_NOTE ->
                recordChangesRequested(pr, author, note.globalId(), note.at(), provider);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Whether a system note's body is one of the three review decisions. */
    public static boolean isReviewDecision(@Nullable String body) {
        if (body == null) return false;
        String stripped = body.strip();
        return APPROVED_SYSTEM_NOTE.equals(stripped)
                || UNAPPROVED_SYSTEM_NOTE.equals(stripped)
                || REQUESTED_CHANGES_SYSTEM_NOTE.equals(stripped);
    }

    /**
     * The approval row for a live approval note that arrived before the {@code approved} merge request
     * hook made one: the same row that hook would make, at the note's time rather than at receipt.
     */
    private void createLiveApproval(PullRequest pr, User approver, Instant approvedAt, IdentityProvider provider) {
        if (pr.getNativeId() == null || approver.getNativeId() == null) {
            return;
        }
        PullRequestReview review = new PullRequestReview();
        review.setNativeId(
                GitLabMergeRequestProcessor.generateApprovalNativeId(pr.getNativeId(), approver.getNativeId()));
        review.setProvider(provider);
        review.setState(PullRequestReview.State.APPROVED);
        review.setHtmlUrl(pr.getHtmlUrl() != null ? pr.getHtmlUrl() : "");
        review.setSubmittedAt(approvedAt);
        review.setCreatedAt(approvedAt);
        review.setUpdatedAt(Instant.now());
        review.setCommitId(pr.getHeadRefOid());
        review.setAuthor(approver);
        review.setPullRequest(pr);
        pr.addReview(reviewRepository.save(review));
    }

    /**
     * Records when an approval was given. GitLab's {@code approvedBy} carries no time, so the approval
     * review is created at the merge request's merged or updated time; the system note "approved this
     * merge request" is the moment the approver acted, and it is the moment a practice about approving
     * before merging needs. The earliest such note by the approver stands; an approval later than the
     * note is moved back, one earlier is left alone.
     *
     * <p>An approval the same person had withdrawn with a note ({@link #recordUnapproval}) is given
     * again by a later note: {@code afterUnapproval} says the caller saw that withdrawal, and only then
     * is a dismissed row re-approved — a row {@code approvedBy} no longer lists was dismissed for a
     * reason this note does not undo, such as a push that reset the approvals.
     *
     * @return the review as it stands after the note, or {@code null} when no approval by this user is
     *     recorded for the merge request
     */
    @Nullable
    PullRequestReview recordApprovalTime(
            PullRequest pr, User approver, Instant approvedAt, IdentityProvider provider, boolean afterUnapproval) {
        PullRequestReview review = approvalReview(pr, approver, provider);
        if (review == null) {
            return null;
        }
        if (review.getState() == PullRequestReview.State.DISMISSED && afterUnapproval) {
            review.setState(PullRequestReview.State.APPROVED);
            review.setDismissed(false);
            review.setSubmittedAt(approvedAt);
            review.setUpdatedAt(Instant.now());
            return reviewRepository.save(review);
        }
        if (review.getState() != PullRequestReview.State.APPROVED) {
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

    /**
     * The system note "unapproved this merge request": the person withdrew their approval, so their
     * approval review is dismissed, as the {@code unapproved} webhook dismisses it. Not a request for
     * changes — that is its own note and its own review.
     *
     * @return the review as it stands after the note, or {@code null} when no approval by this user is
     *     recorded for the merge request
     */
    @Nullable
    PullRequestReview recordUnapproval(PullRequest pr, User approver, Instant unapprovedAt, IdentityProvider provider) {
        PullRequestReview review = approvalReview(pr, approver, provider);
        if (review == null || review.getState() != PullRequestReview.State.APPROVED) {
            return review;
        }
        review.setState(PullRequestReview.State.DISMISSED);
        review.setDismissed(true);
        review.setUpdatedAt(unapprovedAt);
        return reviewRepository.save(review);
    }

    /**
     * The system note "requested changes": one CHANGES_REQUESTED review by its author at the note's
     * time. Each such note is its own decision, so the row is keyed by the note, and a re-sync finds the
     * row it made before. The approval review of the same person is left alone: GitLab keeps the two
     * apart, and so does this record.
     */
    @Nullable
    PullRequestReview recordChangesRequested(
            PullRequest pr, User reviewer, String noteGlobalId, Instant requestedAt, IdentityProvider provider) {
        if (reviewer.getNativeId() == null || provider.getId() == null) {
            return null;
        }
        long nativeId = generateChangesRequestedNativeId(noteGlobalId, reviewer.getNativeId());
        PullRequestReview review = reviewRepository
                .findByNativeIdAndProviderId(nativeId, provider.getId())
                .orElseGet(() -> {
                    PullRequestReview created = new PullRequestReview();
                    created.setNativeId(nativeId);
                    created.setProvider(provider);
                    created.setState(PullRequestReview.State.CHANGES_REQUESTED);
                    created.setHtmlUrl(pr.getHtmlUrl() != null ? pr.getHtmlUrl() : "");
                    created.setCreatedAt(requestedAt);
                    created.setAuthor(reviewer);
                    created.setPullRequest(pr);
                    return created;
                });
        review.setSubmittedAt(requestedAt);
        review.setUpdatedAt(Instant.now());
        PullRequestReview saved = reviewRepository.save(review);
        pr.addReview(saved);
        return saved;
    }

    private @Nullable PullRequestReview approvalReview(PullRequest pr, User approver, IdentityProvider provider) {
        if (pr.getNativeId() == null || approver.getNativeId() == null || provider.getId() == null) {
            return null;
        }
        long approvalNativeId =
                GitLabMergeRequestProcessor.generateApprovalNativeId(pr.getNativeId(), approver.getNativeId());
        return reviewRepository
                .findByNativeIdAndProviderId(approvalNativeId, provider.getId())
                .orElse(null);
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
     * Uses FNV-1a over {@code key + "|" + authorNativeId}. Collisions
     * with the bit-packed approval native IDs ({@code (mr<<32)|user}) are
     * astronomically unlikely because the two schemes occupy different hash spaces;
     * any collision would surface as a DB unique-constraint violation and is logged.
     */
    public static long generateCommentedReviewNativeId(String discussionGlobalId, long authorNativeId) {
        return deterministicNativeId(discussionGlobalId, authorNativeId);
    }

    /**
     * The native ID of a CHANGES_REQUESTED review, hashed like a COMMENTED one but over the system
     * note's GID with a prefix, so it never coincides with the COMMENTED review of a discussion.
     */
    public static long generateChangesRequestedNativeId(String noteGlobalId, long authorNativeId) {
        return deterministicNativeId("requested-changes:" + noteGlobalId, authorNativeId);
    }

    private static long deterministicNativeId(String key, long authorNativeId) {
        long hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
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
