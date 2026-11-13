package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment.dto.GitHubPullRequestReviewCommentEventDTO;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitHub pull_request_review_comment webhook events.
 * <p>
 * This handler processes review comments on Pull Requests. The webhook includes
 * embedded PR data, which we use to create a stub PR entity if the PR
 * webhook hasn't arrived yet (message ordering issue).
 * <p>
 * <b>Key Design Decision:</b> We use processCreatedWithParentCreation to handle the case
 * where the review comment webhook arrives before the PR webhook. This creates a minimal
 * PR stub from the webhook data, which will be hydrated later by the PR webhook
 * or scheduled sync. This prevents data loss that occurred due to message ordering.
 *
 * @see GitHubPullRequestReviewCommentProcessor#processCreatedWithParentCreation
 */
@Component
public class GitHubPullRequestReviewCommentMessageHandler
        extends AbstractIntegrationMessageHandler<GitHubPullRequestReviewCommentEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubPullRequestReviewCommentMessageHandler.class);

    private final ProcessingContextFactory contextFactory;
    private final GitHubPullRequestReviewCommentProcessor commentProcessor;

    GitHubPullRequestReviewCommentMessageHandler(
            ProcessingContextFactory contextFactory,
            GitHubPullRequestReviewCommentProcessor commentProcessor,
            EventPayloadDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "repository." + GitHubEventType.PULL_REQUEST_REVIEW_COMMENT.getValue(),
                GitHubPullRequestReviewCommentEventDTO.class,
                deserializer,
                transactionTemplate);
        this.contextFactory = contextFactory;
        this.commentProcessor = commentProcessor;
    }

    @Override
    protected void handleEvent(GitHubPullRequestReviewCommentEventDTO event) {
        var commentDto = event.comment();
        var prDto = event.pullRequest();

        if (commentDto == null || prDto == null) {
            log.warn("Received pull_request_review_comment event with missing data: action={}", event.action());
            return;
        }

        log.debug(
                "Received pull_request_review_comment event: action={}, prNumber={}, commentId={}, repoName={}",
                event.action(),
                prDto.number(),
                commentDto.id(),
                event.repository() != null ? sanitizeForLog(event.repository().fullName()) : "unknown");

        ProcessingContext context = contextFactory.forWebhookEvent(event).orElse(null);
        if (context == null) {
            return;
        }

        Long prId = prDto.getDatabaseId();
        if (prId == null && event.actionType() != GitHubEventAction.PullRequestReviewComment.CREATED) {
            log.warn("Skipped review comment mutation: reason=missingPullRequestId");
            return;
        }

        // Delegate to processor based on action
        // Use processCreatedWithParentCreation to handle the case where the PR webhook
        // hasn't arrived yet. This creates a minimal PR stub from the webhook data
        // instead of losing the comment.
        switch (event.actionType()) {
            case GitHubEventAction.PullRequestReviewComment.DELETED ->
                commentProcessor.processDeleted(commentDto.id(), Objects.requireNonNull(prId), context);
            case GitHubEventAction.PullRequestReviewComment.CREATED ->
                commentProcessor.processCreatedWithParentCreation(commentDto, prDto, context);
            case GitHubEventAction.PullRequestReviewComment.EDITED ->
                commentProcessor.processEdited(commentDto, Objects.requireNonNull(prId), context);
            default ->
                log.debug(
                        "Skipped pull_request_review_comment event: reason=unhandledAction, action={}", event.action());
        }
    }
}
