package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreview;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreview.dto.GitHubPullRequestReviewEventDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitHub pull_request_review webhook events.
 * <p>
 * This handler processes reviews on Pull Requests. The webhook includes
 * embedded PR data, which we use to create a stub PR entity if the PR
 * webhook hasn't arrived yet (message ordering issue).
 * <p>
 * <b>Key Design Decision:</b> We use processWithParentCreation to handle the case
 * where the review webhook arrives before the PR webhook. This creates a minimal
 * PR stub from the webhook data, which will be hydrated later by the PR webhook
 * or scheduled sync. This prevents data loss that occurred due to message ordering.
 *
 * @see GitHubPullRequestReviewProcessor#processWithParentCreation
 */
@Component
public class GitHubPullRequestReviewMessageHandler
        extends AbstractIntegrationMessageHandler<GitHubPullRequestReviewEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubPullRequestReviewMessageHandler.class);

    private final ProcessingContextFactory contextFactory;
    private final GitHubPullRequestReviewProcessor reviewProcessor;

    GitHubPullRequestReviewMessageHandler(
            ProcessingContextFactory contextFactory,
            GitHubPullRequestReviewProcessor reviewProcessor,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "repository." + GitHubEventType.PULL_REQUEST_REVIEW.getValue(),
                GitHubPullRequestReviewEventDTO.class,
                deserializer,
                transactionTemplate);
        this.contextFactory = contextFactory;
        this.reviewProcessor = reviewProcessor;
    }

    @Override
    protected void handleEvent(GitHubPullRequestReviewEventDTO event) {
        var reviewDto = event.review();
        var prDto = event.pullRequest();

        if (reviewDto == null || prDto == null) {
            log.warn("Received pull_request_review event with missing data: action={}", event.action());
            return;
        }

        log.debug(
                "Received pull_request_review event: action={}, prNumber={}, reviewId={}, repoName={}",
                event.action(),
                prDto.number(),
                reviewDto.id(),
                event.repository() != null ? sanitizeForLog(event.repository().fullName()) : "unknown");

        ProcessingContext context = contextFactory.forWebhookEvent(event).orElse(null);
        if (context == null) {
            return;
        }

        // Delegate to processor based on action
        // Use processWithParentCreation to handle the case where the PR webhook
        // hasn't arrived yet. This creates a minimal PR stub from the webhook data
        // instead of losing the review.
        if (event.actionType() == GitHubEventAction.PullRequestReview.DISMISSED) {
            if (reviewDto.id() == null) {
                log.warn("Skipped review dismissal: reason=missingReviewId");
                return;
            }
            reviewProcessor.processDismissed(reviewDto.id(), reviewDto.updatedAt(), context);
        } else {
            reviewProcessor.processWithParentCreation(reviewDto, prDto, context);
        }
    }
}
