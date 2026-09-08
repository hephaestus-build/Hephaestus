package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Evicts affected mentor context keys after commit. Async listeners keep cache work off the
 * publishing transaction's caller.
 */
@Component
@RequiredArgsConstructor
public class MentorContextInvalidator {

    private static final Logger log = LoggerFactory.getLogger(MentorContextInvalidator.class);

    /** Caches scoped by {@code workspaceId + ":" + userId}. */
    private static final List<String> PER_USER_CACHES = List.of(
            "mentor_user_context",
            "mentor_workspace_context",
            // The authored-work context (RecentAuthoredWorkContentSource) is keyed per
            // workspaceId:developerId and goes stale on the same PR/issue/review events.
            "mentor_authored_work_context");

    private final CacheManager cacheManager;
    private final WorkspaceRepository workspaceRepository;
    private final PullRequestRepository pullRequestRepository;

    /**
     * PR updates change the per-user activity counts: open PRs, merged-this-week, unresolved
     * threads, review states, etc. We invalidate per-user contexts for both the author and
     * (since the PR may have just been merged) the mergedBy user.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPullRequestUpdated(ScmDomainEvent.PullRequestUpdated event) {
        Long workspaceId = resolveWorkspaceId(event.context());
        if (workspaceId == null) return;
        Long authorId = event.pullRequest() != null ? event.pullRequest().authorId() : null;
        evictPerUser(workspaceId, authorId);
        // Also evict for the merger (if any) — their reviews-given/PRs-merged counters may
        // have changed even if they are not the PR author.
        Long mergedById = event.pullRequest() != null ? event.pullRequest().mergedById() : null;
        if (mergedById != null && !mergedById.equals(authorId)) {
            evictPerUser(workspaceId, mergedById);
        }
    }

    /**
     * Issue updates affect the assigned-work surface. Invalidate the workspace context for the
     * author (who sees their work list change).
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIssueUpdated(ScmDomainEvent.IssueUpdated event) {
        Long workspaceId = resolveWorkspaceId(event.context());
        if (workspaceId == null) return;
        Long authorId = event.issue() != null ? event.issue().authorId() : null;
        evictPerUser(workspaceId, authorId);
    }

    /**
     * Review submissions change the reviewer's "reviews-given-this-week" counter AND, indirectly,
     * the PR author's "reviews-received-this-week" / "pending-review-requests" / "unresolved-threads"
     * counters. Without this, the user-context cache would lie for up to its TTL after every code
     * review — which is the highest-frequency event in the mentor's per-user surface.
     *
     * <p>{@code @Transactional(REQUIRES_NEW)} is mandatory: {@code AFTER_COMMIT} runs after the
     * originating transaction has closed, so the {@code findById} below needs its own session
     * to materialise {@code pr.getAuthor()} without a {@code LazyInitializationException}.
     *
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onReviewSubmitted(ScmDomainEvent.ReviewSubmitted event) {
        evictForReview(event.context(), event.review());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onReviewEdited(ScmDomainEvent.ReviewEdited event) {
        evictForReview(event.context(), event.review());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onReviewDismissed(ScmDomainEvent.ReviewDismissed event) {
        evictForReview(event.context(), event.review());
    }

    private void evictForReview(EventContext context, ScmEventPayload.ReviewData review) {
        Long workspaceId = resolveWorkspaceId(context);
        if (workspaceId == null || review == null) return;
        evictPerUser(workspaceId, review.authorId());
        Long prAuthorId = pullRequestRepository
                .findById(review.pullRequestId())
                .map(pr -> pr.getAuthor() != null ? pr.getAuthor().getId() : null)
                .orElse(null);
        if (prAuthorId != null && !prAuthorId.equals(review.authorId())) {
            evictPerUser(workspaceId, prAuthorId);
        }
    }

    /**
     * Best-effort resolution of {@code workspaceId} from event context. Returns {@code null}
     * when the event lacks the necessary linkage — the caller is then a no-op.
     */
    private @Nullable Long resolveWorkspaceId(@Nullable EventContext context) {
        if (context == null || context.repository() == null) return null;
        // The ScmDomainEvent context carries a RepositoryRef — we resolve to workspace via the
        // RepositoryToMonitor join (same approach the context queries use).
        return workspaceRepository
                .findWorkspaceIdByRepositoryId(context.repository().id())
                .orElse(null);
    }

    private void evictPerUser(Long workspaceId, @Nullable Long userId) {
        evictPerUser(workspaceId, userId, PER_USER_CACHES);
    }

    private void evictPerUser(Long workspaceId, @Nullable Long userId, List<String> cacheNames) {
        if (workspaceId == null || userId == null) return;
        String key = workspaceId + ":" + userId;
        for (String cacheName : cacheNames) {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.evict(key);
                log.debug("Evicted {} key={}", cacheName, key);
            }
        }
    }
}
