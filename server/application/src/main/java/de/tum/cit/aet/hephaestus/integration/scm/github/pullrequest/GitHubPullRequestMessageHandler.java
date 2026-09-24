package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.GitHubPullRequestDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.GitHubPullRequestEventDTO;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles all GitHub pull request webhook events.
 */
@Component
public class GitHubPullRequestMessageHandler extends AbstractIntegrationMessageHandler<GitHubPullRequestEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubPullRequestMessageHandler.class);

    private final ProcessingContextFactory contextFactory;
    private final GitHubPullRequestProcessor prProcessor;
    private final GitHubPullRequestSyncService syncService;
    private final TransactionTemplate transactionTemplate;

    public GitHubPullRequestMessageHandler(
            ProcessingContextFactory contextFactory,
            GitHubPullRequestProcessor prProcessor,
            GitHubPullRequestSyncService syncService,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "repository." + GitHubEventType.PULL_REQUEST.getValue(),
                GitHubPullRequestEventDTO.class,
                deserializer,
                transactionTemplate);
        this.contextFactory = contextFactory;
        this.prProcessor = prProcessor;
        this.syncService = syncService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * The event's own view is stored in the short transaction; then, for the actions that change what
     * only GraphQL carries, the pull request is read again outside it, so the GraphQL round trip holds
     * no database connection.
     */
    @Override
    protected void dispatchEvent(GitHubPullRequestEventDTO event) {
        ProcessingContext context = transactionTemplate.execute(status -> handleEventAndReturnContext(event));
        GitHubPullRequestDTO prDto = event.pullRequest();
        if (context != null && context.repository() != null && prDto != null && refreshesAfter(event.actionType())) {
            syncService.refreshPullRequest(context.scopeId(), context.repository(), prDto.number());
        }
    }

    /** The actions after which the closing references, the rollup or the decision may have moved. */
    static boolean refreshesAfter(GitHubEventAction.PullRequest action) {
        return switch (action) {
            case OPENED, EDITED, SYNCHRONIZE, CLOSED, REOPENED -> true;
            default -> false;
        };
    }

    @Override
    protected void handleEvent(GitHubPullRequestEventDTO event) {
        handleEventAndReturnContext(event);
    }

    private @Nullable ProcessingContext handleEventAndReturnContext(GitHubPullRequestEventDTO event) {
        GitHubPullRequestDTO prDto = event.pullRequest();

        if (prDto == null) {
            log.warn("Received pull_request event with missing data: action={}", event.action());
            return null;
        }

        log.debug(
                "Received pull_request event: action={}, prNumber={}, repoName={}",
                event.action(),
                prDto.number(),
                event.repository() != null ? sanitizeForLog(event.repository().fullName()) : "unknown");

        ProcessingContext context = contextFactory.forWebhookEvent(event).orElse(null);
        if (context == null) {
            return null;
        }

        routeToProcessor(event, prDto, context);
        return context;
    }

    private void routeToProcessor(
            GitHubPullRequestEventDTO event, GitHubPullRequestDTO prDto, ProcessingContext context) {
        switch (event.actionType()) {
            case GitHubEventAction.PullRequest.OPENED,
                    GitHubEventAction.PullRequest.EDITED,
                    GitHubEventAction.PullRequest.ASSIGNED,
                    GitHubEventAction.PullRequest.UNASSIGNED,
                    GitHubEventAction.PullRequest.MILESTONED,
                    GitHubEventAction.PullRequest.DEMILESTONED,
                    GitHubEventAction.PullRequest.AUTO_MERGE_ENABLED,
                    GitHubEventAction.PullRequest.AUTO_MERGE_DISABLED,
                    GitHubEventAction.PullRequest.REVIEW_REQUEST_REMOVED,
                    GitHubEventAction.PullRequest.ENQUEUED,
                    GitHubEventAction.PullRequest.DEQUEUED,
                    GitHubEventAction.PullRequest.REVIEW_REQUESTED -> prProcessor.process(prDto, context);
            case GitHubEventAction.PullRequest.REOPENED -> prProcessor.processReopened(prDto, context);
            case GitHubEventAction.PullRequest.CLOSED -> prProcessor.processClosed(prDto, context);
            case GitHubEventAction.PullRequest.READY_FOR_REVIEW -> prProcessor.processReadyForReview(prDto, context);
            case GitHubEventAction.PullRequest.CONVERTED_TO_DRAFT ->
                prProcessor.processConvertedToDraft(prDto, context);
            case GitHubEventAction.PullRequest.SYNCHRONIZE -> prProcessor.processSynchronize(prDto, context);
            case GitHubEventAction.PullRequest.LABELED -> {
                if (event.label() != null) {
                    prProcessor.processLabeled(prDto, event.label(), context);
                } else {
                    prProcessor.process(prDto, context);
                }
            }
            case GitHubEventAction.PullRequest.UNLABELED -> {
                if (event.label() != null) {
                    prProcessor.processUnlabeled(prDto, event.label(), context);
                } else {
                    prProcessor.process(prDto, context);
                }
            }
            default -> {
                log.debug("Skipped pull_request event: reason=unhandledAction, action={}", event.action());
                prProcessor.process(prDto, context);
            }
        }
    }
}
