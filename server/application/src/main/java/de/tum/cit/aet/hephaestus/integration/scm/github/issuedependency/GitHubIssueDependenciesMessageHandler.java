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
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuedependency.dto.GitHubIssueDependenciesEventDTO;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitHub {@code issue_dependencies} webhook events: an issue's blocked-by relationships changed.
 * <p>
 * GitHub reports each change from both sides, and both deliveries apply the same change, so either one alone
 * keeps the relationship current. A blocking issue in another repository is stored under that repository, and
 * only when that repository is synchronized; {@link GitHubIssueDependencySyncService#syncDependenciesForScope}
 * reconciles whatever a delivery missed.
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
        Long blockedIssueId = storedId(
                blockedIssueDto, contextFactory.forRelatedIssue(context, event.blockedIssueRepo(), event.action()));
        Long blockingIssueId = storedId(
                blockingIssueDto, contextFactory.forRelatedIssue(context, event.blockingIssueRepo(), event.action()));
        if (blockedIssueId == null || blockingIssueId == null) {
            log.debug("Skipped issue_dependencies event: reason=issueNotStored, action={}", event.action());
            return;
        }

        issueDependencySyncService.processIssueDependencyEvent(blockedIssueId, blockingIssueId, action.isAdded());
    }

    /** Stores one side of the relationship and returns its row id; nothing when its repository is not synchronized. */
    private @Nullable Long storedId(GitHubIssueDTO issue, Optional<ProcessingContext> context) {
        Issue stored = context.isPresent() ? issueProcessor.process(issue, context.get()) : null;
        return stored == null ? null : stored.getId();
    }
}
