package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryIdentityLookup;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRepositoryMonitors;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Confirms manual Adds with the workspace's credential before granting a monitor in a fresh connection transaction.
 * GitHub's path is here; {@code GitLabManualRepositoryMonitor} owns GitLab's.
 */
@Service
@RequiredArgsConstructor
@WorkspaceAgnostic("Coordinates workspace repository mapping grants against their own provider authority")
public class ManualRepositoryMonitorService {
    private static final String GITHUB_SERVER_URL = "https://github.com";

    private final ManualMonitorGrants grants;
    private final GitLabManualRepositoryMonitor gitLab;
    private final WorkspaceRepositoryMonitors monitors;
    private final RepositoryRepository repositoryRepository;
    private final IdentityProviderLookup identityProviders;
    private final ConnectionService connectionService;
    private final ConnectionQueries connectionQueries;
    private final TransactionTemplate transactionTemplate;
    private final WorkspaceScopeFilter scopeFilter;

    public RepositoryToMonitor add(Workspace workspace, String nameWithOwner) {
        String slug = workspace.getWorkspaceSlug();

        IntegrationKind kind = connectionService
                .findActiveProviderKind(workspace.getId())
                .orElseThrow(() -> new RepositoryProviderNotConnectedException(slug));
        if (kind == IntegrationKind.GITLAB) {
            return gitLab.add(workspace, nameWithOwner);
        }

        if (scopeFilter.isExcluded(nameWithOwner)) {
            throw new RepositoryExcludedFromWorkspacesException(nameWithOwner);
        }

        Long workspaceId = workspace.getId();
        var connection = connectionQueries
                .findActive(workspaceId, IntegrationKind.GITHUB)
                .orElseThrow(() -> new RepositoryProviderNotConnectedException(slug));
        if (!(connection.config() instanceof ConnectionConfig.GitHubPatConfig initialConfig)) {
            throw new RepositoryManagementNotAllowedException(slug);
        }
        // GitHub is asked only on github.com; a token for another server is never sent there to vouch for it.
        if (PersonSourceNamespace.from(
                        IntegrationKind.GITHUB.name(), SourceProviderNamespaces.configuredUrl(initialConfig))
                .filter(namespace -> GITHUB_SERVER_URL.equals(namespace.serverUrl()))
                .isEmpty()) {
            throw new RepositoryOriginNotSupportedException(slug);
        }
        if (monitors.monitorsPath(workspaceId, nameWithOwner)) {
            throw new RepositoryAlreadyMonitoredException(nameWithOwner);
        }
        Long answeredVersion = connection.version();
        RepositoryIdentityLookup.RepositoryIdentity reported =
                grants.confirmWithOwnCredential(IntegrationKind.GITHUB, workspace, nameWithOwner);
        RepositoryToMonitor monitor = transactionTemplate.execute(status -> {
            // Disconnect and credential changes take this same lock; decide from the configuration they committed.
            var current = grants.lockAnsweringConnection(workspaceId, IntegrationKind.GITHUB, connection.id(), slug);
            if (!(current.getConfig() instanceof ConnectionConfig.GitHubPatConfig config)) {
                throw new RepositoryManagementNotAllowedException(slug);
            }
            if (scopeFilter.isExcluded(nameWithOwner) || scopeFilter.isExcluded(reported.fullPath())) {
                throw new RepositoryExcludedFromWorkspacesException(nameWithOwner);
            }
            // A new token or a reconnect keeps the row; only its version shows that GitHub answered an earlier one.
            if (!Objects.equals(current.getVersion(), answeredVersion)) {
                throw new RepositorySelectionChangedException();
            }
            if (monitors.monitorsPath(workspaceId, nameWithOwner)) {
                throw new RepositoryAlreadyMonitoredException(nameWithOwner);
            }
            Long providerId = sourceProviderId(IntegrationKind.GITHUB, config)
                    .orElseThrow(() -> new RepositoryProviderNotConnectedException(slug));
            // The mirror at this path is another repository unless GitHub named this one to the token.
            Repository repository = repositoryRepository
                    .findByNameWithOwnerAndProviderId(nameWithOwner, providerId)
                    .filter(mirrored -> Objects.equals(mirrored.getNativeId(), reported.nativeId()))
                    .orElseThrow(() -> new EntityNotFoundException("Repository", nameWithOwner));
            if (monitors.monitorOf(workspaceId, repository.getNativeId()).isPresent()) {
                throw new RepositoryAlreadyMonitoredException(nameWithOwner);
            }
            return grants.grant(workspaceId, nameWithOwner, repository.getNativeId());
        });
        return Objects.requireNonNull(monitor);
    }

    private Optional<Long> sourceProviderId(IntegrationKind kind, ConnectionConfig config) {
        return PersonSourceNamespace.from(kind.name(), SourceProviderNamespaces.configuredUrl(config))
                .flatMap(namespace -> identityProviders.findId(IdentityProviderType.from(kind), namespace.serverUrl()));
    }
}
