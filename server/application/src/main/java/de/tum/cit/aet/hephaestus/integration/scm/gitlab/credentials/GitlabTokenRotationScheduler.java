package de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWebhookService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class GitlabTokenRotationScheduler {
    private final ConnectionService connections;
    private final WorkspaceRepository workspaces;
    private final GitLabWebhookService webhooks;

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @SchedulerLock(name = "gitlabTokenRotation", lockAtMostFor = "PT2H")
    public void rotateTokens() {
        for (long workspaceId : connections.findWorkspaceIdsWithActiveConnection(IntegrationKind.GITLAB)) {
            workspaces
                    .findById(workspaceId)
                    .filter(workspace -> workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
                    .ifPresent(workspace -> {
                        try {
                            webhooks.rotateTokenIfNeeded(workspace);
                        } catch (RuntimeException e) {
                            log.warn("Scheduled GitLab token check failed: workspaceId={}", workspaceId);
                        }
                    });
        }
    }
}
