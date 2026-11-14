package de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceResumeBlockedException;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceResumeGuard;
import org.springframework.stereotype.Component;

/** GitHub resumes the workspace of an installation it unsuspends, so an admin's resume waits for that. */
@Component
class GitHubSuspensionResumeGuard implements WorkspaceResumeGuard {

    private final ConnectionService connectionService;

    GitHubSuspensionResumeGuard(ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @Override
    public void verifyResumable(long workspaceId) {
        if (connectionService
                .findActive(workspaceId, IntegrationKind.GITHUB)
                .filter(connection ->
                        connection.getAttentionProblem() == IntegrationAttentionChangedEvent.Problem.PROVIDER_SUSPENDED)
                .isPresent()) {
            throw new WorkspaceResumeBlockedException(
                    "GitHub has suspended the Hephaestus app for this workspace. Unsuspend the app in the GitHub"
                            + " settings of the organization or account. The workspace then resumes by itself.");
        }
    }
}
