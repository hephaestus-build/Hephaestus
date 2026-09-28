package de.tum.cit.aet.hephaestus.integration.scm.github.issuedependency;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuedependency.dto.GitHubIssueDependenciesEventDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitHub {@code issue_dependencies} webhook events: an issue's blocked-by relationships changed.
 * <p>
 * Both deliveries of a change apply it, so either one alone keeps the relationship current;
 * {@link GitHubIssueDependencySyncService#syncDependenciesForScope} reconciles what deliveries miss.
 *
 * @see <a href="https://docs.github.com/en/webhooks/webhook-events-and-payloads#issue_dependencies">
 *      GitHub Webhook Events - issue_dependencies</a>
 */
@Component
public class GitHubIssueDependenciesMessageHandler
        extends AbstractIntegrationMessageHandler<GitHubIssueDependenciesEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubIssueDependenciesMessageHandler.class);

    private final ProcessingContextFactory contextFactory;
    private final GitHubIssueProcessor issueProcessor;
    private final GitHubIssueDependencySyncService issueDependencySyncService;

    GitHubIssueDependenciesMessageHandler(
            ProcessingContextFactory contextFactory,
            GitHubIssueProcessor issueProcessor,
            GitHubIssueDependencySyncService issueDependencySyncService,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "repository." + GitHubEventType.ISSUE_DEPENDENCIES.getValue(),
                GitHubIssueDependenciesEventDTO.class,
                deserializer,
                transactionTemplate);
        this.contextFactory = contextFactory;
        this.issueProcessor = issueProcessor;
        this.issueDependencySyncService = issueDependencySyncService;
    }

    @Override
    protected void handleEvent(GitHubIssueDependenciesEventDTO event) {
        GitHubEventAction.IssueDependency action = event.actionType();
        if (action == GitHubEventAction.IssueDependency.UNKNOWN) {
            log.debug("Skipped issue_dependencies event: reason=unhandledAction, action={}", event.action());
            return;
        }

        var blockedIssueDto = event.blockedIssue();
        var blockingIssueDto = event.blockingIssue();
        if (blockedIssueDto == null || blockingIssueDto == null) {
            log.warn("Received issue_dependencies event with missing data: action={}", event.action());
            return;
        }

        log.debug(
                "Received issue_dependencies event: action={}, blockedIssueNumber={}, blockingIssueNumber={}, repoName={}",
                event.action(),
                blockedIssueDto.number(),
                blockingIssueDto.number(),
                event.repository() != null ? sanitizeForLog(event.repository().fullName()) : "unknown");

        ProcessingContext context = contextFactory.forWebhookEvent(event).orElse(null);
        if (context == null) {
            return;
        }

        // The relationship is keyed by the stored rows, whose ids are not GitHub's.
        Long blockedIssueId = contextFactory
                .forRelatedIssue(context, event.blockedIssueRepo(), event.action())
                .map(issueContext -> issueProcessor.process(blockedIssueDto, issueContext))
                .map(Issue::getId)
                .orElse(null);
        Long blockingIssueId = contextFactory
                .forRelatedIssue(context, event.blockingIssueRepo(), event.action())
                .map(issueContext -> issueProcessor.process(blockingIssueDto, issueContext))
                .map(Issue::getId)
                .orElse(null);
        if (blockedIssueId == null || blockingIssueId == null) {
            log.debug("Skipped issue_dependencies event: reason=issueNotStored, action={}", event.action());
            return;
        }

        issueDependencySyncService.processIssueDependencyEvent(blockedIssueId, blockingIssueId, action.isAdded());
    }
}
