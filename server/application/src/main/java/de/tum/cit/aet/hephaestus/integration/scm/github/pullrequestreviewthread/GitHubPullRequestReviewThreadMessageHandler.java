package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewthread;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewthread.dto.GitHubPullRequestReviewThreadEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitHub pull_request_review_thread webhook events.
 */
@Component
public class GitHubPullRequestReviewThreadMessageHandler
        extends AbstractIntegrationMessageHandler<GitHubPullRequestReviewThreadEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubPullRequestReviewThreadMessageHandler.class);

    private final ProcessingContextFactory contextFactory;
    private final GitHubPullRequestProcessor prProcessor;
    private final GitHubPullRequestReviewThreadProcessor threadProcessor;
    private final GitHubUserProcessor userProcessor;

    GitHubPullRequestReviewThreadMessageHandler(
            ProcessingContextFactory contextFactory,
            GitHubPullRequestProcessor prProcessor,
            GitHubPullRequestReviewThreadProcessor threadProcessor,
            GitHubUserProcessor userProcessor,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "repository." + GitHubEventType.PULL_REQUEST_REVIEW_THREAD.getValue(),
                GitHubPullRequestReviewThreadEventDTO.class,
                deserializer,
                transactionTemplate);
        this.contextFactory = contextFactory;
        this.prProcessor = prProcessor;
        this.threadProcessor = threadProcessor;
        this.userProcessor = userProcessor;
    }

    @Override
    protected void handleEvent(GitHubPullRequestReviewThreadEventDTO event) {
        var threadDto = event.thread();
        var prDto = event.pullRequest();

        if (threadDto == null || prDto == null) {
            log.warn("Received pull_request_review_thread event with missing data: action={}", event.action());
            return;
        }

        // Thread ID is the first comment's database ID (see GitHubPullRequestReviewCommentSyncService)
        Long threadId = threadDto.getFirstCommentId();

        log.debug(
                "Received pull_request_review_thread event: action={}, prNumber={}, threadId={}, repoName={}",
                event.action(),
                prDto.number(),
                threadId,
                event.repository() != null ? sanitizeForLog(event.repository().fullName()) : "unknown");

        ProcessingContext context = contextFactory.forWebhookEvent(event).orElse(null);
        if (context == null) {
            return;
        }

        // Ensure PR exists
        prProcessor.process(prDto, context);

        // Delegate thread state changes to processor
        switch (event.actionType()) {
            case GitHubEventAction.PullRequestReviewThread.RESOLVED -> {
                // Thread ID is derived from the first comment. If no comments exist, we cannot process.
                if (threadId == null) {
                    log.warn(
                            "Skipped pull_request_review_thread event: reason=noCommentsInThread, action={}, prNumber={}, repoName={}",
                            event.action(),
                            prDto.number(),
                            event.repository() != null
                                    ? sanitizeForLog(event.repository().fullName())
                                    : "unknown");
                    return;
                }
                // Ensure the sender (who resolved the thread) exists
                User resolvedBy =
                        userProcessor.ensureExists(event.sender(), Objects.requireNonNull(context.providerId()));
                // The payload dates the event; one without a date happened about now, since it just arrived.
                Instant resolvedAt = event.updatedAt() != null ? event.updatedAt() : Instant.now();
                threadProcessor.resolve(threadId, resolvedBy, resolvedAt, context);
            }
            case GitHubEventAction.PullRequestReviewThread.UNRESOLVED -> {
                // Thread ID is derived from the first comment. If no comments exist, we cannot process.
                if (threadId == null) {
                    log.warn(
                            "Skipped pull_request_review_thread event: reason=noCommentsInThread, action={}, prNumber={}, repoName={}",
                            event.action(),
                            prDto.number(),
                            event.repository() != null
                                    ? sanitizeForLog(event.repository().fullName())
                                    : "unknown");
                    return;
                }
                threadProcessor.unresolve(threadId, context);
            }
            default ->
                log.debug(
                        "Skipped pull_request_review_thread event: reason=unhandledAction, action={}", event.action());
        }
    }
}
