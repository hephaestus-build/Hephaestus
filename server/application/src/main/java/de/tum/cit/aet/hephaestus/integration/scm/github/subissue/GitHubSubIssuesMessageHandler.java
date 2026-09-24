package de.tum.cit.aet.hephaestus.integration.scm.github.subissue;

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
import de.tum.cit.aet.hephaestus.integration.scm.github.subissue.dto.GitHubSubIssuesEventDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitHub sub_issues webhook events.
 */
@Component
public class GitHubSubIssuesMessageHandler extends AbstractIntegrationMessageHandler<GitHubSubIssuesEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubSubIssuesMessageHandler.class);

    private final ProcessingContextFactory contextFactory;
    private final GitHubIssueProcessor issueProcessor;
    private final GitHubSubIssueSyncService subIssueSyncService;

    GitHubSubIssuesMessageHandler(
            ProcessingContextFactory contextFactory,
            GitHubIssueProcessor issueProcessor,
            GitHubSubIssueSyncService subIssueSyncService,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "repository." + GitHubEventType.SUB_ISSUES.getValue(),
                GitHubSubIssuesEventDTO.class,
                deserializer,
                transactionTemplate);
        this.contextFactory = contextFactory;
        this.issueProcessor = issueProcessor;
        this.subIssueSyncService = subIssueSyncService;
    }

    @Override
    protected void handleEvent(GitHubSubIssuesEventDTO event) {
        var subIssueDto = event.subIssue();
        var parentIssueDto = event.parentIssue();

        if (subIssueDto == null || parentIssueDto == null) {
            log.warn("Received sub_issues event with missing data: action={}", event.action());
            return;
        }

        log.debug(
                "Received sub_issues event: action={}, parentIssueNumber={}, subIssueNumber={}, repoName={}",
                event.action(),
                parentIssueDto.number(),
                subIssueDto.number(),
                event.repository() != null ? sanitizeForLog(event.repository().fullName()) : "unknown");

        ProcessingContext context = contextFactory.forWebhookEvent(event).orElse(null);
        if (context == null) {
            return;
        }

        // Ensure both issues exist
        Issue parent = issueProcessor.process(parentIssueDto, context);
        Issue child = issueProcessor.process(subIssueDto, context);

        // The relationship is keyed by the stored rows, whose ids are not GitHub's.
        GitHubEventAction.SubIssue action = event.actionType();
        Long subIssueId = child == null ? null : child.getId();
        Long parentIssueId = parent == null ? null : parent.getId();
        if (subIssueId == null || parentIssueId == null) {
            log.warn("Skipped sub_issues event: reason=issueNotStored");
            return;
        }

        // The payload's parent carries GitHub's own rollup after the change; a payload without one
        // leaves the rollup to the next sync.
        if (action.isAdded()) {
            subIssueSyncService.processSubIssueEvent(
                    subIssueId, parentIssueId, true, parentIssueDto.subIssuesSummary());
        } else if (action.isRemoved()) {
            subIssueSyncService.processSubIssueEvent(
                    subIssueId, parentIssueId, false, parentIssueDto.subIssuesSummary());
        } else {
            log.debug("Skipped sub_issues event: reason=unhandledAction, action={}", event.action());
        }
    }
}
