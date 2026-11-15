package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRepositoryMonitors;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A manual Add on a GitLab workspace. GitLab is asked outside any transaction; the write holds the connection
 * lifecycle lock and refuses when the connection that answered changed meanwhile. A renamed project must not acquire
 * a second monitor. No exclusion check: a GitLab project on the private repository's path is another repository.
 */
@Component
@RequiredArgsConstructor
@WorkspaceAgnostic("Coordinates workspace repository mapping grants against their own provider authority")
class GitLabManualRepositoryMonitor {
    private final ManualMonitorGrants grants;
    private final WorkspaceRepositoryMonitors monitors;
    private final ConnectionService connectionService;
    private final ConnectionQueries connectionQueries;
    private final GitLabRepositorySelectionService selection;
    private final TransactionTemplate transactionTemplate;

    RepositoryToMonitor add(Workspace workspace, String nameWithOwner) {
        Long workspaceId = workspace.getId();
        var connection = connectionQueries
                .findActive(workspaceId, IntegrationKind.GITLAB)
                .orElseThrow(() -> new RepositoryProviderNotConnectedException(workspace.getWorkspaceSlug()));
        if (!(connection.config() instanceof ConnectionConfig.GitLabConfig initialConfig)) {
            throw new RepositoryProviderNotConnectedException(workspace.getWorkspaceSlug());
        }
        boolean selected = selection.storedSelection(workspaceId) == RepositorySelection.SELECTED;
        if (monitors.monitorsPath(workspaceId, nameWithOwner)) {
            throw new RepositoryAlreadyMonitoredException(nameWithOwner);
        }
        Long answeredVersion = connection.version();
        // Resolve an explicit Add against GitLab before taking the write lock. The id always comes from GitLab: a path
        // alone cannot tell which excluded project it is or whether it was reused, and a mirror another workspace
        // recorded at this path names a project without granting it to this one.
        long resolvedNativeId = grants.confirmWithOwnCredential(IntegrationKind.GITLAB, workspace, nameWithOwner)
                .nativeId();
        RepositoryToMonitor monitor = transactionTemplate.execute(status -> {
            var current = grants.lockAnsweringConnection(
                    workspaceId, IntegrationKind.GITLAB, connection.id(), workspace.getWorkspaceSlug());
            if (!(current.getConfig() instanceof ConnectionConfig.GitLabConfig currentConfig)
                    || !Objects.equals(currentConfig.serverUrl(), initialConfig.serverUrl())) {
                throw new RepositoryProviderNotConnectedException(workspace.getWorkspaceSlug());
            }
            if (!selected && selection.storedSelection(workspaceId) == RepositorySelection.SELECTED) {
                throw new RepositorySelectionChangedException();
            }
            // A new token or a reconnect keeps the row; only its version shows that GitLab answered an earlier one.
            if (!Objects.equals(current.getVersion(), answeredVersion)) {
                throw new RepositorySelectionChangedException();
            }
            long nativeId = resolvedNativeId;
            if (connectionService
                    .updateGitLabProjectExclusions(workspaceId, exclusions -> exclusions.include(nativeId))
                    .isEmpty()) {
                throw new RepositoryProviderNotConnectedException(workspace.getWorkspaceSlug());
            }
            if (monitors.monitorsPath(workspaceId, nameWithOwner)
                    || !monitors.monitorsOf(workspaceId, nativeId).isEmpty()) {
                throw new RepositoryAlreadyMonitoredException(nameWithOwner);
            }
            return grants.grant(workspaceId, nameWithOwner, nativeId);
        });
        return Objects.requireNonNull(monitor);
    }
}
