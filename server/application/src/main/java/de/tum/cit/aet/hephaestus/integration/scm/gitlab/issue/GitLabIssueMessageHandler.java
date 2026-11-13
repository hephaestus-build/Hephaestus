package de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookContextResolver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.dto.GitLabIssueEventDTO;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitLab issue webhook events.
 * <p>
 * Processes both {@code event_type: "issue"} and {@code event_type: "confidential_issue"} payloads.
 * Both arrive on the same NATS subject ({@code object_kind: "issue"}).
 * <p>
 * Confidential issue events are not ingested — an event whose current {@code confidential} flag is set is
 * not (re)stored. In addition, a public → confidential flip purges any snapshot mirrored while the issue
 * was still public via {@link GitLabIssueProcessor#purgeConfidential} (soft-delete tombstone), so a stale
 * public copy of now-restricted data does not linger.
 * <p>
 * Routes to {@link GitLabIssueProcessor} based on the action:
 * <ul>
 *   <li>{@code open} / {@code update} → {@link GitLabIssueProcessor#process}</li>
 *   <li>{@code close} → {@link GitLabIssueProcessor#processClosed}</li>
 *   <li>{@code reopen} → {@link GitLabIssueProcessor#processReopened}</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabIssueMessageHandler extends AbstractIntegrationMessageHandler<GitLabIssueEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitLabIssueMessageHandler.class);

    private final GitLabIssueProcessor issueProcessor;
    private final GitLabWebhookContextResolver contextResolver;

    GitLabIssueMessageHandler(
            GitLabIssueProcessor issueProcessor,
            GitLabWebhookContextResolver contextResolver,
            EventPayloadDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITLAB,
                GitLabEventType.ISSUE.getValue(),
                GitLabIssueEventDTO.class,
                deserializer,
                transactionTemplate);
        this.issueProcessor = issueProcessor;
        this.contextResolver = contextResolver;
    }

    @Override
    protected void handleEvent(GitLabIssueEventDTO event) {
        if (event.objectAttributes() == null) {
            log.warn("Received issue event with missing object_attributes");
            return;
        }

        if (event.project() == null) {
            log.warn("Received issue event with missing project data");
            return;
        }

        String projectPath = event.project().pathWithNamespace();
        if (projectPath == null || projectPath.isBlank()) {
            log.warn("Received issue event with missing project path");
            return;
        }
        String safeProjectPath = Objects.requireNonNullElse(sanitizeForLog(projectPath), "<unknown>");
        GitLabEventAction action = event.actionType();

        ProcessingContext context = contextResolver.resolve(projectPath, action.getValue(), "issue");
        if (context == null) {
            return;
        }

        // Confidential issues are never (re)ingested. But an issue that flips public → confidential
        // must also have any snapshot we mirrored while it was public purged — otherwise a stale
        // public copy of now-restricted data lingers. Resolve the context first so we know the
        // repository, then tombstone.
        if (event.isConfidential()) {
            log.debug(
                    "Confidential issue event: purging any public snapshot, iid={}",
                    event.objectAttributes().iid());
            issueProcessor.purgeConfidential(event, context);
            return;
        }

        log.info(
                "Processing issue event: projectPath={}, iid={}, action={}",
                safeProjectPath,
                event.objectAttributes().iid(),
                action);

        switch (action) {
            case OPEN -> issueProcessor.process(event, context);
            // GitLab uses UPDATE for title, label and due-date edits; its diff distinguishes them.
            case UPDATE -> issueProcessor.processUpdated(event, context);
            case CLOSE -> issueProcessor.processClosed(event, context);
            case REOPEN -> issueProcessor.processReopened(event, context);
            default -> log.debug("Unhandled issue action: projectPath={}, action={}", safeProjectPath, action);
        }
    }
}
