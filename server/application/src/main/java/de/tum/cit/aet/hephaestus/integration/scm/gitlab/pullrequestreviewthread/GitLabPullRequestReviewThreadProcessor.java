package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewthread;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processor for GitLab merge request discussion threads.
 * <p>
 * Maps GitLab discussions (which are threaded containers of notes) to
 * {@link PullRequestReviewThread} entities. A GitLab discussion with
 * {@code position != null} on any note is a diff discussion that maps
 * to a review thread.
 * <p>
 * GitLab Discussion IDs are SHA hex hashes (e.g. {@code gid://gitlab/Discussion/6a9c1750b37d...}),
 * NOT numeric. We store the full GID as {@code nodeId} and use a deterministic
 * hash as {@code nativeId} for the composite unique constraint.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabPullRequestReviewThreadProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitLabPullRequestReviewThreadProcessor.class);

    private final PullRequestReviewThreadRepository threadRepository;
    private final ApplicationEventPublisher eventPublisher;

    public GitLabPullRequestReviewThreadProcessor(
            PullRequestReviewThreadRepository threadRepository, ApplicationEventPublisher eventPublisher) {
        this.threadRepository = threadRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Groups the discussion-level data needed to find or create a review thread.
     * <p>
     * Position fields ({@code filePath}, {@code newLine}, {@code oldLine},
     * {@code side}, {@code commitSha}, {@code originalCommitSha}) come from the
     * root diff note's {@code position} and are copied onto the thread so
     * downstream consumers can index review threads by file/line/side without
     * joining through comments.
     */
    public record ThreadData(
            String discussionGlobalId,
            boolean resolved,
            @Nullable User resolvedBy,
            @Nullable String filePath,
            @Nullable Integer newLine,
            @Nullable Integer oldLine,
            PullRequestReviewComment.@Nullable Side side,
            @Nullable String commitSha,
            @Nullable String originalCommitSha,
            @Nullable Boolean outdated,
            @Nullable Instant createdAt,
            /** When GitLab says the discussion was resolved; the discussion states it, the sync copies it. */
            @Nullable Instant resolvedAt) {
        /** Overload for callers that do not carry the resolution time. */
        public ThreadData(
                String discussionGlobalId,
                boolean resolved,
                @Nullable User resolvedBy,
                @Nullable String filePath,
                @Nullable Integer newLine,
                @Nullable Integer oldLine,
                PullRequestReviewComment.@Nullable Side side,
                @Nullable String commitSha,
                @Nullable String originalCommitSha,
                @Nullable Boolean outdated,
                @Nullable Instant createdAt) {
            this(
                    discussionGlobalId,
                    resolved,
                    resolvedBy,
                    filePath,
                    newLine,
                    oldLine,
                    side,
                    commitSha,
                    originalCommitSha,
                    outdated,
                    createdAt,
                    null);
        }

        /** Backward-compatible overload for callers that don't carry outdated data. */
        public ThreadData(
                String discussionGlobalId,
                boolean resolved,
                @Nullable User resolvedBy,
                @Nullable String filePath,
                @Nullable Integer newLine,
                @Nullable Integer oldLine,
                PullRequestReviewComment.@Nullable Side side,
                @Nullable String commitSha,
                @Nullable String originalCommitSha,
                @Nullable Instant createdAt) {
            this(
                    discussionGlobalId,
                    resolved,
                    resolvedBy,
                    filePath,
                    newLine,
                    oldLine,
                    side,
                    commitSha,
                    originalCommitSha,
                    null,
                    createdAt);
        }

        /** Backward-compatible overload for callers that don't carry side/SHA data. */
        public ThreadData(
                String discussionGlobalId,
                boolean resolved,
                @Nullable User resolvedBy,
                @Nullable String filePath,
                @Nullable Integer newLine,
                @Nullable Instant createdAt) {
            this(discussionGlobalId, resolved, resolvedBy, filePath, newLine, null, null, null, null, null, createdAt);
        }
    }

    /**
     * Groups the webhook-level data needed to find or create a webhook thread. {@code line} is the
     * {@link #anchoredLine anchored line}, resolved by the caller from the note's position.
     */
    public record WebhookThreadData(
            long noteNativeId,
            @Nullable String filePath,
            @Nullable Integer line,
            @Nullable Instant createdAt,
            @Nullable Instant updatedAt) {}

    /**
     * The line a GitLab position anchors on, the pair of
     * {@link de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment.GitLabPullRequestReviewCommentProcessor#deriveSide}:
     * {@code new_line} on the RIGHT side, and {@code old_line} when the note sits on a removed line and
     * only the LEFT side has one. Null when the position names no line, which is how GitLab reports a
     * hunk a later push dropped.
     */
    public static @Nullable Integer anchoredLine(@Nullable Integer newLine, @Nullable Integer oldLine) {
        return newLine != null ? newLine : oldLine;
    }

    /**
     * Finds or creates a review thread from a GitLab discussion.
     * <p>
     * Since GitLab Discussion IDs are hex hashes (not numeric), we look up by
     * {@code nodeId + providerId} and use a deterministic hash as {@code nativeId}.
     *
     * @param data the discussion-level data (global ID, resolution state, file position, timestamp)
     * @param pr the parent pull request
     * @param provider the git provider
     * @param scopeId the scope ID for event context
     * @return the thread entity (never null)
     */
    @Transactional
    public PullRequestReviewThread findOrCreateThread(
            ThreadData data, PullRequest pr, IdentityProvider provider, Long scopeId) {
        Long providerId = Objects.requireNonNull(provider.getId());

        return threadRepository
                .findByNodeIdAndProviderId(data.discussionGlobalId(), providerId)
                .map(existing -> updateThread(existing, data, pr, scopeId))
                .orElseGet(() -> createThread(data, pr, provider, scopeId));
    }

    /**
     * Finds or creates a thread from a webhook diff note.
     * <p>
     * Uses the discussion_id from the webhook payload (hashed via {@link #deterministicNativeId})
     * as the thread nativeId. This matches the GraphQL sync's discussion-based threads,
     * enabling correct thread grouping without reconciliation.
     *
     * @param data the webhook-level data (thread native ID, file position, timestamps)
     * @param pr the parent pull request
     * @param provider the git provider
     * @return the thread entity (never null)
     */
    @Transactional
    public PullRequestReviewThread findOrCreateWebhookThread(
            WebhookThreadData data, PullRequest pr, IdentityProvider provider) {
        Long providerId = Objects.requireNonNull(provider.getId());

        return threadRepository
                .findByNativeIdAndProviderId(data.noteNativeId(), providerId)
                .orElseGet(() -> {
                    PullRequestReviewThread thread = new PullRequestReviewThread();
                    thread.setNativeId(data.noteNativeId());
                    thread.setProvider(provider);
                    thread.setPullRequest(pr);
                    thread.setPath(data.filePath());
                    thread.setLine(data.line());
                    thread.setState(PullRequestReviewThread.State.UNRESOLVED);
                    thread.setCreatedAt(data.createdAt());
                    thread.setUpdatedAt(data.updatedAt());

                    PullRequestReviewThread saved = threadRepository.save(thread);
                    log.debug("Created webhook thread: nativeId={}, path={}", data.noteNativeId(), data.filePath());
                    return saved;
                });
    }

    private PullRequestReviewThread updateThread(
            PullRequestReviewThread existing, ThreadData data, PullRequest pr, Long scopeId) {
        PullRequestReviewThread.State previousState = existing.getState();
        boolean changed = false;

        PullRequestReviewThread.State newState =
                data.resolved() ? PullRequestReviewThread.State.RESOLVED : PullRequestReviewThread.State.UNRESOLVED;

        if (existing.getState() != newState) {
            existing.setState(newState);
            changed = true;
        }
        if (data.resolved() && data.resolvedBy() != null && existing.getResolvedBy() == null) {
            existing.setResolvedBy(data.resolvedBy());
            changed = true;
        }
        if (data.resolved() && data.resolvedAt() != null && !data.resolvedAt().equals(existing.getResolvedAt())) {
            existing.setResolvedAt(data.resolvedAt());
            changed = true;
        }
        if (!data.resolved() && (existing.getResolvedBy() != null || existing.getResolvedAt() != null)) {
            existing.setResolvedBy(null);
            existing.setResolvedAt(null);
            changed = true;
        }

        // Backfill position metadata populated in later syncs. We only fill when the
        // current row is null so that a manual correction upstream is never clobbered
        // and legacy GitHub rows (which arrive via a different processor) are untouched.
        if (existing.getPath() == null && data.filePath() != null) {
            existing.setPath(data.filePath());
            changed = true;
        }
        Integer line = anchoredLine(data.newLine(), data.oldLine());
        if (existing.getLine() == null && line != null) {
            existing.setLine(line);
            changed = true;
        }
        if (existing.getSide() == null && data.side() != null) {
            existing.setSide(data.side());
            changed = true;
        }
        if (existing.getStartSide() == null && data.side() != null) {
            existing.setStartSide(data.side());
            changed = true;
        }
        if (existing.getCommitSha() == null && data.commitSha() != null) {
            existing.setCommitSha(data.commitSha());
            changed = true;
        }
        if (existing.getOriginalCommitSha() == null && data.originalCommitSha() != null) {
            existing.setOriginalCommitSha(data.originalCommitSha());
            changed = true;
        }
        if (existing.getOutdated() == null && data.outdated() != null) {
            existing.setOutdated(data.outdated());
            changed = true;
        }

        if (!changed) {
            return existing;
        }
        existing.setUpdatedAt(Instant.now());
        PullRequestReviewThread saved = threadRepository.save(existing);
        log.debug("Updated thread: id={}, state={}", saved.getId(), newState);

        if (previousState != newState) {
            publishThreadStateEvent(saved, pr, scopeId);
        }
        return saved;
    }

    private PullRequestReviewThread createThread(
            ThreadData data, PullRequest pr, IdentityProvider provider, Long scopeId) {
        long nativeId = deterministicNativeId(data.discussionGlobalId());

        PullRequestReviewThread thread = new PullRequestReviewThread();
        thread.setNativeId(nativeId);
        thread.setNodeId(data.discussionGlobalId());
        thread.setProvider(provider);
        thread.setPullRequest(pr);
        thread.setPath(data.filePath());
        thread.setLine(anchoredLine(data.newLine(), data.oldLine()));
        thread.setSide(data.side());
        // GraphQL DiffPosition has no line_range so the thread inherits a single-line
        // anchor; startSide mirrors side to match GitHub semantics for single-line threads.
        thread.setStartSide(data.side());
        thread.setCommitSha(data.commitSha());
        thread.setOriginalCommitSha(data.originalCommitSha());
        thread.setOutdated(data.outdated());
        thread.setState(
                data.resolved() ? PullRequestReviewThread.State.RESOLVED : PullRequestReviewThread.State.UNRESOLVED);
        if (data.resolved() && data.resolvedBy() != null) {
            thread.setResolvedBy(data.resolvedBy());
        }
        if (data.resolved()) {
            thread.setResolvedAt(data.resolvedAt());
        }
        thread.setCreatedAt(data.createdAt());
        thread.setUpdatedAt(data.createdAt());

        PullRequestReviewThread saved = threadRepository.save(thread);
        log.debug(
                "Created thread from GitLab discussion: nodeId={}, path={}",
                data.discussionGlobalId(),
                data.filePath());

        // Publish resolved event if the thread was already resolved when first synced
        if (data.resolved()) {
            publishThreadStateEvent(saved, pr, scopeId);
        }

        return saved;
    }

    private void publishThreadStateEvent(PullRequestReviewThread thread, PullRequest pr, Long scopeId) {
        ScmEventPayload.ReviewThreadData.from(thread).ifPresent(threadData -> {
            RepositoryRef repoRef = pr.getRepository() != null ? RepositoryRef.from(pr.getRepository()) : null;
            if (repoRef == null) {
                return;
            }
            EventContext ctx = EventContext.forSync(scopeId, repoRef, IdentityProviderType.GITLAB);

            if (thread.getState() == PullRequestReviewThread.State.RESOLVED) {
                eventPublisher.publishEvent(new ScmDomainEvent.ReviewThreadResolved(threadData, ctx));
            } else {
                eventPublisher.publishEvent(new ScmDomainEvent.ReviewThreadUnresolved(threadData, ctx));
            }
        });
    }

    /**
     * Generates a deterministic positive nativeId from a GitLab Discussion GID.
     * <p>
     * GitLab Discussion IDs are hex hashes (not numeric), so we use a hash function
     * to produce a stable Long. This is collision-resistant enough for our use case
     * since it's scoped per provider.
     */
    public static long deterministicNativeId(String discussionGlobalId) {
        // Use the same approach as java.lang.String.hashCode() but with long accumulator
        // for better distribution. FNV-1a inspired.
        long hash = 0xcbf29ce484222325L; // FNV offset basis
        for (int i = 0; i < discussionGlobalId.length(); i++) {
            hash ^= discussionGlobalId.charAt(i);
            hash *= 0x100000001b3L; // FNV prime
        }
        // Ensure positive — nativeId must be positive
        return hash & Long.MAX_VALUE;
    }
}
