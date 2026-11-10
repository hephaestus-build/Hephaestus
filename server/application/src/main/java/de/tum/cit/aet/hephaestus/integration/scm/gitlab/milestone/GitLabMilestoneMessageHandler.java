package de.tum.cit.aet.hephaestus.integration.scm.gitlab.milestone;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookContextResolver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.milestone.dto.GitLabMilestoneDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.milestone.dto.GitLabMilestoneEventDTO;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitLab milestone webhook events.
 * <p>
 * Routes to {@link GitLabMilestoneProcessor} based on the action:
 * <ul>
 *   <li>{@code create} / {@code update} / {@code close} / {@code reopen} &rarr;
 *       {@link GitLabMilestoneProcessor#process} (state is encoded in the DTO)</li>
 * </ul>
 * <p>
 * GitLab does not send a webhook when a milestone is deleted — stale milestones
 * are cleaned up by the GraphQL sync service's stale removal logic.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabMilestoneMessageHandler extends AbstractIntegrationMessageHandler<GitLabMilestoneEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitLabMilestoneMessageHandler.class);

    private final GitLabMilestoneProcessor milestoneProcessor;
    private final GitLabWebhookContextResolver contextResolver;

    GitLabMilestoneMessageHandler(
            GitLabMilestoneProcessor milestoneProcessor,
            GitLabWebhookContextResolver contextResolver,
            EventPayloadDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITLAB,
                GitLabEventType.MILESTONE.getValue(),
                GitLabMilestoneEventDTO.class,
                deserializer,
                transactionTemplate);
        this.milestoneProcessor = milestoneProcessor;
        this.contextResolver = contextResolver;
    }

    @Override
    protected void handleEvent(GitLabMilestoneEventDTO event) {
        if (event.objectAttributes() == null) {
            log.warn("Received milestone event with missing object_attributes");
            return;
        }

        if (event.project() == null) {
            log.warn("Received milestone event with missing project data");
            return;
        }

        String projectPath = event.project().pathWithNamespace();
        if (projectPath == null || projectPath.isBlank()) {
            log.warn("Received milestone event with missing project path");
            return;
        }
        String safeProjectPath = Objects.requireNonNullElse(sanitizeForLog(projectPath), "<unknown>");
        GitLabEventAction action = event.actionType();

        log.info(
                "Processing milestone event: projectPath={}, iid={}, action={}",
                safeProjectPath,
                event.objectAttributes().iid(),
                action);

        ProcessingContext context = contextResolver.resolve(projectPath, action.getValue(), "milestone");
        if (context == null) {
            return;
        }

        GitLabMilestoneDTO dto = GitLabMilestoneDTO.fromWebhookEvent(event);
        if (dto == null) {
            log.warn("Failed to create milestone DTO from webhook event: projectPath={}", safeProjectPath);
            return;
        }

        milestoneProcessor.process(dto, Objects.requireNonNull(context.repository()), context);
    }
}
