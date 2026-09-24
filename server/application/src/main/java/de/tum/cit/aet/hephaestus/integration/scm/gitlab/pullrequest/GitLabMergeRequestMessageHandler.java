package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookContextResolver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.dto.GitLabMergeRequestEventDTO;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitLab merge request webhook events.
 * <p>
 * Routes to {@link GitLabMergeRequestProcessor} based on the action:
 * <ul>
 *   <li>{@code open} / {@code update} → {@link GitLabMergeRequestProcessor#process}</li>
 *   <li>{@code close} → {@link GitLabMergeRequestProcessor#processClosed}</li>
 *   <li>{@code reopen} → {@link GitLabMergeRequestProcessor#processReopened}</li>
 *   <li>{@code merge} → {@link GitLabMergeRequestProcessor#processMerged}</li>
 *   <li>{@code approved} → {@link GitLabMergeRequestProcessor#processApproved}</li>
 *   <li>{@code unapproved} → {@link GitLabMergeRequestProcessor#processUnapproved}</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabMergeRequestMessageHandler extends AbstractIntegrationMessageHandler<GitLabMergeRequestEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitLabMergeRequestMessageHandler.class);

    private final GitLabMergeRequestProcessor mergeRequestProcessor;
    private final GitLabWebhookContextResolver contextResolver;
    private final GitLabClosingIssueClient closingIssueClient;
    private final TransactionTemplate transactionTemplate;

    GitLabMergeRequestMessageHandler(
            GitLabMergeRequestProcessor mergeRequestProcessor,
            GitLabWebhookContextResolver contextResolver,
            GitLabClosingIssueClient closingIssueClient,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITLAB,
                GitLabEventType.MERGE_REQUEST.getValue(),
                GitLabMergeRequestEventDTO.class,
                deserializer,
                transactionTemplate);
        this.mergeRequestProcessor = mergeRequestProcessor;
        this.contextResolver = contextResolver;
        this.closingIssueClient = closingIssueClient;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * The event is stored in the short transaction; an opened or updated merge request then has the
     * issues it closes read from GitLab outside it — the webhook stores the {@code updated_at} the sync
     * later compares against, so the sync would not read them for this change.
     */
    @Override
    protected void dispatchEvent(GitLabMergeRequestEventDTO event) {
        ProcessingContext context = transactionTemplate.execute(status -> handleEventAndReturnContext(event));
        Repository repository = context == null ? null : context.repository();
        Long scopeId = context == null ? null : context.scopeId();
        var attributes = event.objectAttributes();
        var project = event.project();
        if (repository == null
                || scopeId == null
                || attributes == null
                || attributes.iid() == null
                || project == null
                || project.id() == null) {
            return;
        }
        if (event.actionType() != GitLabEventAction.OPEN && event.actionType() != GitLabEventAction.UPDATE) {
            return;
        }
        int iid = attributes.iid();
        List<Integer> closing = closingIssueClient.closesIssues(scopeId, project.id(), iid);
        if (closing != null) {
            transactionTemplate.executeWithoutResult(
                    status -> mergeRequestProcessor.replaceClosingIssues(repository, iid, closing));
        }
    }

    @Override
    protected void handleEvent(GitLabMergeRequestEventDTO event) {
        handleEventAndReturnContext(event);
    }

    private @Nullable ProcessingContext handleEventAndReturnContext(GitLabMergeRequestEventDTO event) {
        if (event.objectAttributes() == null) {
            log.warn("Received merge request event with missing object_attributes");
            return null;
        }

        if (event.project() == null) {
            log.warn("Received merge request event with missing project data");
            return null;
        }

        if (event.isConfidential()) {
            log.debug(
                    "Skipped confidential merge request event: iid={}",
                    event.objectAttributes().iid());
            return null;
        }

        String projectPath = event.project().pathWithNamespace();
        if (projectPath == null || projectPath.isBlank()) {
            log.warn("Received merge request event with missing project path");
            return null;
        }
        String safeProjectPath = Objects.requireNonNullElse(sanitizeForLog(projectPath), "<unknown>");
        GitLabEventAction action = event.actionType();

        log.info(
                "Processing merge request event: projectPath={}, iid={}, action={}",
                safeProjectPath,
                event.objectAttributes().iid(),
                action);

        ProcessingContext context = contextResolver.resolve(projectPath, action.getValue(), "merge request");
        if (context == null) {
            return null;
        }

        switch (action) {
            case OPEN, UPDATE -> mergeRequestProcessor.process(event, context);
            case CLOSE -> mergeRequestProcessor.processClosed(event, context);
            case REOPEN -> mergeRequestProcessor.processReopened(event, context);
            case MERGE -> mergeRequestProcessor.processMerged(event, context);
            case APPROVED -> mergeRequestProcessor.processApproved(event, context);
            case UNAPPROVED -> mergeRequestProcessor.processUnapproved(event, context);
            case APPROVAL, UNAPPROVAL ->
                log.debug(
                        "Skipped group-level approval rule event: projectPath={}, action={}", safeProjectPath, action);
            default -> log.debug("Unhandled merge request action: projectPath={}, action={}", safeProjectPath, action);
        }
        return context;
    }
}
