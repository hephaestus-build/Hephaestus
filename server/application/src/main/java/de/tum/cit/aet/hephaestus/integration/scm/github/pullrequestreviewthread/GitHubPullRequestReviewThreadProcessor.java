package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewthread;

import de.tum.cit.aet.hephaestus.integration.core.ProviderStateVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processor for GitHub pull request review threads.
 */
@Service
public class GitHubPullRequestReviewThreadProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitHubPullRequestReviewThreadProcessor.class);

    private final PullRequestReviewThreadRepository threadRepository;
    private final ApplicationEventPublisher eventPublisher;

    public GitHubPullRequestReviewThreadProcessor(
            PullRequestReviewThreadRepository threadRepository, ApplicationEventPublisher eventPublisher) {
        this.threadRepository = threadRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * @param resolvedAt when the event says the thread was resolved — the only source of that moment
     *     on GitHub, whose GraphQL thread carries none
     * @param eventAt the event's own date, which orders it against the thread's other resolve and unresolve events
     */
    @Transactional
    public boolean resolve(
            Long threadId,
            @Nullable User resolvedBy,
            Instant resolvedAt,
            @Nullable Instant eventAt,
            @NonNull ProcessingContext context) {
        if (threadId == null) {
            log.debug("Skipped thread resolve: reason=nullThreadId");
            return false;
        }

        return lockAndFind(threadId, context)
                .map(thread -> {
                    if (isOlderThanStoredChange(eventAt, thread, "resolve")) {
                        return true;
                    }
                    // A redelivered resolve changes nothing and records no second activity. Sync marks a
                    // thread resolved without a date or an event, so a resolve after it still records one.
                    if (thread.getState() == PullRequestReviewThread.State.RESOLVED && thread.getResolvedAt() != null) {
                        log.debug("Skipped thread resolve: reason=alreadyResolved, threadId={}", threadId);
                        recordChange(thread, eventAt);
                        return true;
                    }
                    thread.setState(PullRequestReviewThread.State.RESOLVED);
                    thread.setResolvedAt(resolvedAt);
                    thread.setResolutionChangedAt(eventAt);
                    if (resolvedBy != null) {
                        thread.setResolvedBy(resolvedBy);
                    }
                    thread = threadRepository.save(thread);
                    ScmEventPayload.ReviewThreadData.from(thread)
                            .ifPresent(threadData -> eventPublisher.publishEvent(
                                    new ScmDomainEvent.ReviewThreadResolved(threadData, EventContext.from(context))));
                    log.info(
                            "Resolved thread: threadId={}, resolvedById={}",
                            threadId,
                            resolvedBy != null ? resolvedBy.getId() : null);
                    return true;
                })
                .orElseGet(() -> {
                    log.debug("Skipped thread resolve: reason=threadNotFound, threadId={}", threadId);
                    return false;
                });
    }

    /** @param eventAt the event's own date, which orders it against the thread's other resolve and unresolve events */
    @Transactional
    public boolean unresolve(Long threadId, @Nullable Instant eventAt, @NonNull ProcessingContext context) {
        if (threadId == null) {
            log.debug("Skipped thread unresolve: reason=nullThreadId");
            return false;
        }

        return lockAndFind(threadId, context)
                .map(thread -> {
                    if (isOlderThanStoredChange(eventAt, thread, "unresolve")) {
                        return true;
                    }
                    if (thread.getState() == PullRequestReviewThread.State.UNRESOLVED) {
                        log.debug("Skipped thread unresolve: reason=alreadyUnresolved, threadId={}", threadId);
                        recordChange(thread, eventAt);
                        return true;
                    }
                    thread.setState(PullRequestReviewThread.State.UNRESOLVED);
                    thread.setResolvedBy(null);
                    thread.setResolvedAt(null);
                    thread.setResolutionChangedAt(eventAt);
                    thread = threadRepository.save(thread);
                    ScmEventPayload.ReviewThreadData.from(thread)
                            .ifPresent(threadData -> eventPublisher.publishEvent(
                                    new ScmDomainEvent.ReviewThreadUnresolved(threadData, EventContext.from(context))));
                    log.info("Unresolved thread: threadId={}", threadId);
                    return true;
                })
                .orElseGet(() -> {
                    log.debug("Skipped thread unresolve: reason=threadNotFound, threadId={}", threadId);
                    return false;
                });
    }

    /** The row lock makes the compare with the stored resolution change and the write after it one step. */
    private Optional<PullRequestReviewThread> lockAndFind(Long threadId, ProcessingContext context) {
        long providerId = Objects.requireNonNull(context.providerId());
        threadRepository.lockByNativeIdAndProviderId(threadId, providerId);
        return threadRepository.findByNativeIdAndProviderId(threadId, providerId);
    }

    /**
     * A newer act that finds the state it sets still dates the state, so that an older act of the other kind that
     * arrives after it changes nothing.
     */
    private void recordChange(PullRequestReviewThread thread, @Nullable Instant eventAt) {
        if (eventAt != null && !ProviderStateVersion.cannotReplace(eventAt, thread.getResolutionChangedAt())) {
            thread.setResolutionChangedAt(eventAt);
            threadRepository.save(thread);
        }
    }

    /**
     * A redelivery can arrive hours after a later resolve or unresolve, and must not flip the thread back
     * (sync-lifecycle.md). Events of the same second keep their arrival order: GitHub dates them to the second, and
     * a redelivery of the same act finds the state it would set.
     */
    private static boolean isOlderThanStoredChange(
            @Nullable Instant eventAt, PullRequestReviewThread thread, String action) {
        if (!ProviderStateVersion.isOlder(eventAt, thread.getResolutionChangedAt())) {
            return false;
        }
        log.debug(
                "Skipped thread {}: reason=olderThanStoredChange, threadId={}, storedChangeAt={}, eventAt={}",
                action,
                thread.getNativeId(),
                thread.getResolutionChangedAt(),
                eventAt);
        return true;
    }
}
