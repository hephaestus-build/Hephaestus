package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewthread;

import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.time.Instant;
import java.util.Objects;
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
     */
    @Transactional
    public boolean resolve(
            Long threadId, @Nullable User resolvedBy, Instant resolvedAt, @NonNull ProcessingContext context) {
        if (threadId == null) {
            log.debug("Skipped thread resolve: reason=nullThreadId");
            return false;
        }

        return threadRepository
                .findByNativeIdAndProviderId(threadId, Objects.requireNonNull(context.providerId()))
                .map(thread -> {
                    thread.setState(PullRequestReviewThread.State.RESOLVED);
                    thread.setResolvedAt(resolvedAt);
                    if (resolvedBy != null) {
                        thread.setResolvedBy(resolvedBy);
                    }
                    thread = threadRepository.save(thread);
                    ScmEventPayload.ReviewThreadData.from(thread)
                            .ifPresent(threadData -> eventPublisher.publishEvent(
                                    new ScmDomainEvent.ReviewThreadResolved(threadData, EventContext.from(context))));
                    log.info(
                            "Resolved thread: threadId={}, resolvedByLogin={}",
                            threadId,
                            resolvedBy != null ? resolvedBy.getLogin() : null);
                    return true;
                })
                .orElseGet(() -> {
                    log.debug("Skipped thread resolve: reason=threadNotFound, threadId={}", threadId);
                    return false;
                });
    }

    @Transactional
    public boolean unresolve(Long threadId, @NonNull ProcessingContext context) {
        if (threadId == null) {
            log.debug("Skipped thread unresolve: reason=nullThreadId");
            return false;
        }

        return threadRepository
                .findByNativeIdAndProviderId(threadId, Objects.requireNonNull(context.providerId()))
                .map(thread -> {
                    thread.setState(PullRequestReviewThread.State.UNRESOLVED);
                    thread.setResolvedBy(null);
                    thread.setResolvedAt(null);
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
}
