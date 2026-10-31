package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.dto.GitLabProjectEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The handler for GitLab project events ({@code project_create}, {@code project_destroy}, {@code project_rename},
 * {@code project_transfer} and {@code project_update}), normalized to the "project" event key by the webhook receiver.
 *
 * <p>{@link GitLabRouteAdmission} does the work before this handler runs: it stores the project as GitLab reports it
 * to the connection and adjusts the workspace's monitor. The payload's id and path are never written, and the shared
 * repository row is never deleted, since GitLab not reporting a project to one connection does not prove it is gone.
 * This handler exists so the dispatcher routes the event to admission.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabProjectEventMessageHandler extends AbstractIntegrationMessageHandler<GitLabProjectEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitLabProjectEventMessageHandler.class);

    GitLabProjectEventMessageHandler(NatsMessageDeserializer deserializer, TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITLAB,
                GitLabEventType.PROJECT.getValue(),
                GitLabProjectEventDTO.class,
                deserializer,
                transactionTemplate);
    }

    @Override
    protected void handleEvent(GitLabProjectEventDTO event) {
        if (GitLabRouteAdmission.current().isEmpty()) {
            log.warn("Skipped project event: reason=noAdmittedRoute, projectId={}", event.projectId());
            return;
        }
        log.debug(
                "Handled project event through admission: eventName={}, path={}, projectId={}",
                event.eventName(),
                sanitizeForLog(event.pathWithNamespace()),
                event.projectId());
    }
}
