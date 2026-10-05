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
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
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
 * review per {@code (author, discussion)} cluster so that inline feedback is attributed
 * to a review, as on GitHub. Approvals are handled separately (see
 * {@code GitLabMergeRequestProcessor}).
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

        // Parity with GitHub: a PR author replying on their own MR produces no Review entity.
        // A synthesised COMMENTED review would read as a review of someone else's work; the
        // author's notes stay discussion comments.
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

    public static final String APPROVED_SYSTEM_NOTE = "approved this merge request";

    public static final String UNAPPROVED_SYSTEM_NOTE = "unapproved this merge request";
    public static final String REQUESTED_CHANGES_SYSTEM_NOTE = "requested changes";

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

    /**
     * Records typed request-changes notes. Approval and withdrawal notes only invalidate readiness:
     * GitLab writes them asynchronously without the approval's act time, and their author may have acted again
     * (<a href="https://gitlab.com/gitlab-org/gitlab/-/blob/v19.4.1-ee/app/workers/merge_requests/create_approval_note_worker.rb">provider source</a>).
     * Current approvals belong to the native approval snapshot, not note replay.
     *
     * @return whether the note requires a new readiness read
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean recordSystemNote(PullRequest pr, User author, SystemNote note, IdentityProvider provider) {
        Long authorNativeId = author.getNativeId();
        if (authorNativeId == null) {
            return false;
        }
        return switch (note.body().strip()) {
            case APPROVED_SYSTEM_NOTE, UNAPPROVED_SYSTEM_NOTE -> note.live();
            case REQUESTED_CHANGES_SYSTEM_NOTE -> {
                boolean recorded = changesRequestedReview(author, note.globalId(), provider) != null;
                yield recordChangesRequested(pr, author, note.globalId(), note.at(), provider) != null && !recorded;
            }
            default -> false;
        };
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
     * The system note "requested changes": one CHANGES_REQUESTED review by its author, at the time GitLab recorded
     * the note. Each such note is its own decision, so the row is keyed by the note, and a re-sync finds the row it
     * made before. The approval of the same person is GitLab's approval snapshot's to settle.
     */
    @Nullable
    PullRequestReview recordChangesRequested(
            PullRequest pr, User reviewer, String noteGlobalId, Instant requestedAt, IdentityProvider provider) {
        if (reviewer.getNativeId() == null || provider.getId() == null) {
            return null;
        }
        long nativeId = generateChangesRequestedNativeId(noteGlobalId, reviewer.getNativeId());
        PullRequestReview review = Optional.ofNullable(changesRequestedReview(reviewer, noteGlobalId, provider))
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

    private @Nullable PullRequestReview changesRequestedReview(
            User reviewer, String noteGlobalId, IdentityProvider provider) {
        if (reviewer.getNativeId() == null || provider.getId() == null) {
            return null;
        }
        return reviewRepository
                .findByNativeIdAndProviderId(
                        generateChangesRequestedNativeId(noteGlobalId, reviewer.getNativeId()), provider.getId())
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
     * Hashes the system-note GID with a separate namespace from COMMENTED discussion reviews.
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
