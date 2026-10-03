package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewthread;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * <p>
 * A diff note webhook and the GraphQL read both name a discussion by its GID — the webhook builds it from the
 * {@code discussion_id} it carries — and find its thread by that GID or its hash, completing a {@code nodeId} a
 * thread stored earlier lacks. A thread found under another merge request, holding another discussion's GID, or
 * two threads for one discussion, are not this discussion's, and nothing is linked to them.
 * <p>
 * A thread's resolution is set only by {@link #applyDiscussionRead}, which takes a whole read of the merge request's
 * discussions begun after the last one it recorded, and creates the thread of a discussion not stored yet; the other
 * paths find or create a thread for its comments without a resolution. Every path finds and creates under the merge
 * request's row lock, so that one discussion gets one thread and no read creates a thread another has already
 * resolved.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabPullRequestReviewThreadProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitLabPullRequestReviewThreadProcessor.class);

    private final PullRequestReviewThreadRepository threadRepository;
    private final PullRequestRepository pullRequestRepository;
    private final ApplicationEventPublisher eventPublisher;

    public GitLabPullRequestReviewThreadProcessor(
            PullRequestReviewThreadRepository threadRepository,
            PullRequestRepository pullRequestRepository,
            ApplicationEventPublisher eventPublisher) {
        this.threadRepository = threadRepository;
        this.pullRequestRepository = pullRequestRepository;
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
     *
     * @param resolved whether GitLab says the discussion is resolved; null when the read did not say, which changes
     *     nothing
     * @param resolvedAt when GitLab says the discussion was resolved; the discussion states it, the sync copies it
     */
    public record ThreadData(
            String discussionGlobalId,
            @Nullable Boolean resolved,
            @Nullable User resolvedBy,
            @Nullable String filePath,
            @Nullable Integer newLine,
            @Nullable Integer oldLine,
            PullRequestReviewComment.@Nullable Side side,
            @Nullable String commitSha,
            @Nullable String originalCommitSha,
            @Nullable Boolean outdated,
            @Nullable Instant createdAt,
            @Nullable Instant resolvedAt) {
        /** The same discussion, resolved by {@code user}. */
        public ThreadData withResolvedBy(@Nullable User user) {
            return new ThreadData(
                    discussionGlobalId,
                    resolved,
                    user,
                    filePath,
                    newLine,
                    oldLine,
                    side,
                    commitSha,
                    originalCommitSha,
                    outdated,
                    createdAt,
                    resolvedAt);
        }

        /** The same discussion with its resolution left unsaid. */
        ThreadData withoutResolution() {
            return new ThreadData(
                    discussionGlobalId,
                    null,
                    null,
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
    }

    /**
     * Groups the webhook-level data needed to find or create a webhook thread. {@code line} is the
     * {@link #anchoredLine anchored line}, resolved by the caller from the note's position.
     *
     * @param discussionGlobalId the discussion's GID where the webhook named its discussion; then {@code noteNativeId}
     *     is its hash
     */
    public record WebhookThreadData(
            long noteNativeId,
            @Nullable String filePath,
            @Nullable Integer line,
            @Nullable Instant createdAt,
            @Nullable Instant updatedAt,
            @Nullable String discussionGlobalId) {}

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
     * Finds or creates a review thread from a GitLab discussion, for its comments. The discussion's resolution in
     * {@code data} is not recorded here: a new thread is unresolved until {@link #applyDiscussionRead} records a read
     * of it.
     *
     * @param data the discussion-level data (global ID, resolution state, file position, timestamp)
     * @param pr the parent pull request
     * @param provider the git provider
     * @param scopeId the scope ID for event context
     * @return the thread entity, or null when the stored thread with this identity is not this discussion's
     */
    @Transactional
    public @Nullable PullRequestReviewThread findOrCreateThread(
            ThreadData data, PullRequest pr, IdentityProvider provider, Long scopeId) {
        pullRequestRepository.lockById(pr.getId());
        Stored stored = stored(data.discussionGlobalId(), pr, provider);
        if (stored.conflict()) {
            return null;
        }
        PullRequestReviewThread thread = stored.thread();
        ThreadData metadata = data.withoutResolution();
        return thread != null
                ? updateThread(thread, metadata, pr, scopeId)
                : createThread(metadata, pr, provider, scopeId);
    }

    /**
     * Records the resolution a whole read of merge request {@code iid}'s discussions found on their stored threads,
     * unless a read begun at or after {@code requestedAt} was recorded first. An accepted read is recorded even where it
     * changes nothing, so that an older one arriving later cannot undo it. A discussion with no stored thread gets one,
     * with the resolution and position the read found; its comments link to it later. Holds the merge request's row
     * lock, as its other writers do.
     *
     * @param requestedAt when the read asked GitLab for its first page
     * @return whether the read was recorded
     */
    @Transactional
    public boolean applyDiscussionRead(
            Repository repository,
            int iid,
            Instant requestedAt,
            List<ThreadData> read,
            IdentityProvider provider,
            Long scopeId) {
        PullRequest pr = pullRequestRepository
                .findForUpdateByRepositoryIdAndNumber(repository.getId(), iid)
                .orElse(null);
        if (pr == null) {
            return false;
        }
        Instant recorded = pr.getDiscussionsObservedAt();
        if (recorded != null && !requestedAt.isAfter(recorded)) {
            log.debug("Skipped discussion read: reason=notNewer, pullRequestId={}", pr.getId());
            return false;
        }
        pr.setDiscussionsObservedAt(requestedAt);
        for (ThreadData data : read) {
            Stored stored = stored(data.discussionGlobalId(), pr, provider);
            PullRequestReviewThread thread = stored.thread();
            if (thread != null) {
                updateThread(thread, data, pr, scopeId);
            } else if (!stored.conflict()) {
                createThread(data, pr, provider, scopeId);
            }
        }
        return true;
    }

    /** The thread stored for a discussion, if any; {@code conflict} when a thread under its identity is not its own. */
    private record Stored(@Nullable PullRequestReviewThread thread, boolean conflict) {}

    private Stored stored(String discussionGlobalId, PullRequest pr, IdentityProvider provider) {
        Long providerId = Objects.requireNonNull(provider.getId());
        long nativeId = deterministicNativeId(discussionGlobalId);
        Optional<PullRequestReviewThread> byNode =
                threadRepository.findByNodeIdAndProviderId(discussionGlobalId, providerId);
        Optional<PullRequestReviewThread> byNative = threadRepository.findByNativeIdAndProviderId(nativeId, providerId);
        if (byNode.isPresent()
                && byNative.isPresent()
                && !byNode.get().getId().equals(byNative.get().getId())) {
            log.warn("Skipped discussion: reason=twoStoredThreads, nodeId={}", discussionGlobalId);
            return new Stored(null, true);
        }
        PullRequestReviewThread thread = byNode.or(() -> byNative).orElse(null);
        if (thread == null) {
            return new Stored(null, false);
        }
        if (thread.getNodeId() != null && !thread.getNodeId().equals(discussionGlobalId)) {
            log.warn("Skipped discussion: reason=anotherDiscussionsThread, nodeId={}", discussionGlobalId);
            return new Stored(null, true);
        }
        PullRequest parent = thread.getPullRequest();
        if (parent == null || !parent.getId().equals(pr.getId())) {
            log.warn("Skipped discussion: reason=belongsToAnotherMergeRequest, nodeId={}", discussionGlobalId);
            return new Stored(null, true);
        }
        return new Stored(thread, false);
    }

    /**
     * Finds or creates a thread from a webhook diff note.
     * <p>
     * A webhook that names its discussion finds and creates the thread as the GraphQL sync does; one that names
     * none keeps its thread under the note's own id.
     *
     * @param data the webhook-level data (thread native ID, discussion GID, file position, timestamps)
     * @param pr the parent pull request
     * @param provider the git provider
     * @return the thread entity, or null when the stored thread with this identity is not this discussion's
     */
    @Transactional
    public @Nullable PullRequestReviewThread findOrCreateWebhookThread(
            WebhookThreadData data, PullRequest pr, IdentityProvider provider) {
        Long providerId = Objects.requireNonNull(provider.getId());
        String discussionGlobalId = data.discussionGlobalId();
        pullRequestRepository.lockById(pr.getId());

        Optional<PullRequestReviewThread> existing;
        if (discussionGlobalId != null) {
            Stored stored = stored(discussionGlobalId, pr, provider);
            if (stored.conflict()) {
                return null;
            }
            existing = Optional.ofNullable(stored.thread());
            existing.filter(thread -> thread.getNodeId() == null).ifPresent(thread -> {
                thread.setNodeId(discussionGlobalId);
                threadRepository.save(thread);
            });
        } else {
            existing = threadRepository.findByNativeIdAndProviderId(data.noteNativeId(), providerId);
            // A note id is unique on the instance: a thread stored under another merge request is not this one's.
            PullRequest parent =
                    existing.map(PullRequestReviewThread::getPullRequest).orElse(null);
            if (existing.isPresent() && (parent == null || !parent.getId().equals(pr.getId()))) {
                log.warn(
                        "Skipped webhook thread: reason=belongsToAnotherMergeRequest, nativeId={}",
                        data.noteNativeId());
                return null;
            }
        }
        return existing.orElseGet(() -> {
            PullRequestReviewThread thread = new PullRequestReviewThread();
            thread.setNativeId(
                    discussionGlobalId != null ? deterministicNativeId(discussionGlobalId) : data.noteNativeId());
            if (discussionGlobalId != null) {
                thread.setNodeId(discussionGlobalId);
            }
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

        if (existing.getNodeId() == null) {
            existing.setNodeId(data.discussionGlobalId());
            changed = true;
        }

        Boolean resolved = data.resolved();
        PullRequestReviewThread.State newState = resolved == null
                ? previousState
                : resolved ? PullRequestReviewThread.State.RESOLVED : PullRequestReviewThread.State.UNRESOLVED;

        if (existing.getState() != newState) {
            existing.setState(newState);
            changed = true;
        }
        // The read names who resolved it as it stands: a reopening and a new resolution between reads leave no trace.
        User resolver = data.resolvedBy();
        User storedResolver = existing.getResolvedBy();
        if (Boolean.TRUE.equals(resolved)
                && resolver != null
                && (storedResolver == null || !Objects.equals(storedResolver.getId(), resolver.getId()))) {
            existing.setResolvedBy(resolver);
            changed = true;
        }
        if (Boolean.TRUE.equals(resolved)
                && data.resolvedAt() != null
                && !data.resolvedAt().equals(existing.getResolvedAt())) {
            existing.setResolvedAt(data.resolvedAt());
            changed = true;
        }
        if (Boolean.FALSE.equals(resolved) && (existing.getResolvedBy() != null || existing.getResolvedAt() != null)) {
            existing.setResolvedBy(null);
            existing.setResolvedAt(null);
            changed = true;
        }

        // Backfill position metadata populated in later syncs. We only fill when the
        // current row is null so that a manual correction upstream is never clobbered
        // and GitHub rows (written by a different processor) are untouched.
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
        boolean resolved = Boolean.TRUE.equals(data.resolved());
        thread.setState(resolved ? PullRequestReviewThread.State.RESOLVED : PullRequestReviewThread.State.UNRESOLVED);
        if (resolved && data.resolvedBy() != null) {
            thread.setResolvedBy(data.resolvedBy());
        }
        if (resolved) {
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
        if (resolved) {
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
