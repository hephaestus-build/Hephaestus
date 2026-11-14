package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.event.RepositoryMonitoringChangedEvent;
import de.tum.cit.aet.hephaestus.core.exception.DataIntegrityViolationConstraints;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionCommands;
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
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.RepositoryMonitorCommands;
import de.tum.cit.aet.hephaestus.workspace.spi.RepositoryMonitorSources;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRepositoryMonitors;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Confirms manual Adds with the workspace's credential before granting a monitor in a fresh connection transaction. */
@Service
@WorkspaceAgnostic("Coordinates workspace repository mapping grants against their own provider authority")
public class ManualRepositoryMonitorService {
    private static final String GITHUB_SERVER_URL = "https://github.com";

    private final WorkspaceRepositoryMonitors monitors;
    private final RepositoryMonitorSources monitorSources;
    private final RepositoryMonitorCommands monitorCommands;
    private final RepositoryRepository repositoryRepository;
    private final IdentityProviderLookup identityProviders;
    private final ConnectionService connectionService;
    private final ConnectionCommands connectionCommands;
    private final ConnectionQueries connectionQueries;
    private final TransactionTemplate transactionTemplate;
    private final Map<IntegrationKind, RepositoryIdentityLookup> repositoryIdentityLookups;
    private final GitLabRepositorySelectionService selection;
    private final ApplicationEventPublisher eventPublisher;

    public ManualRepositoryMonitorService(
            WorkspaceRepositoryMonitors monitors,
            RepositoryMonitorSources monitorSources,
            RepositoryMonitorCommands monitorCommands,
            RepositoryRepository repositoryRepository,
            IdentityProviderLookup identityProviders,
            ConnectionService connectionService,
            ConnectionCommands connectionCommands,
            ConnectionQueries connectionQueries,
            TransactionTemplate transactionTemplate,
            List<RepositoryIdentityLookup> repositoryIdentityLookupList,
            GitLabRepositorySelectionService selection,
            ApplicationEventPublisher eventPublisher) {
        this.monitors = monitors;
        this.monitorSources = monitorSources;
        this.monitorCommands = monitorCommands;
        this.repositoryRepository = repositoryRepository;
        this.identityProviders = identityProviders;
        this.connectionService = connectionService;
        this.connectionCommands = connectionCommands;
        this.connectionQueries = connectionQueries;
        this.transactionTemplate = transactionTemplate;
        this.selection = selection;
        this.eventPublisher = eventPublisher;
        Map<IntegrationKind, RepositoryIdentityLookup> identityLookupMap = new EnumMap<>(IntegrationKind.class);
        for (RepositoryIdentityLookup lookup : repositoryIdentityLookupList) {
            identityLookupMap.put(lookup.kind(), lookup);
        }
        this.repositoryIdentityLookups = identityLookupMap;
    }

    public RepositoryToMonitor add(Workspace workspace, String nameWithOwner) {
        String slug = workspace.getWorkspaceSlug();

        IntegrationKind kind = connectionService
                .findActiveProviderKind(workspace.getId())
                .orElseThrow(() -> new RepositoryProviderNotConnectedException(slug));
        if (kind == IntegrationKind.GITLAB) {
            return addGitLabRepositoryToMonitor(workspace, nameWithOwner);
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
                confirmWithOwnCredential(IntegrationKind.GITHUB, workspace, nameWithOwner);
        RepositoryToMonitor monitor = transactionTemplate.execute(status -> {
            // Disconnect and credential changes take this same lock; decide from the configuration they committed.
            var current = lockAnsweringConnection(workspaceId, IntegrationKind.GITHUB, connection.id(), slug);
            if (!(current.getConfig() instanceof ConnectionConfig.GitHubPatConfig config)) {
                throw new RepositoryManagementNotAllowedException(slug);
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
            RepositoryToMonitor saved = insertMonitor(workspaceId, nameWithOwner, repository.getNativeId());
            eventPublisher.publishEvent(new RepositoryMonitoringChangedEvent(
                    workspaceId, monitorSources.sourceRepositoryIds(saved.getId())));
            return saved;
        });
        return Objects.requireNonNull(monitor);
    }

    // GitLab is asked outside any transaction; the write holds the connection lifecycle lock and refuses when the
    // connection that answered changed meanwhile. A renamed project must not acquire a second monitor.
    private RepositoryToMonitor addGitLabRepositoryToMonitor(Workspace workspace, String nameWithOwner) {
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
        long resolvedNativeId = confirmWithOwnCredential(IntegrationKind.GITLAB, workspace, nameWithOwner)
                .nativeId();
        RepositoryToMonitor monitor = transactionTemplate.execute(status -> {
            var current = lockAnsweringConnection(
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
            RepositoryToMonitor saved = insertMonitor(workspaceId, nameWithOwner, nativeId);
            eventPublisher.publishEvent(new RepositoryMonitoringChangedEvent(
                    workspaceId, monitorSources.sourceRepositoryIds(saved.getId())));
            return saved;
        });
        return Objects.requireNonNull(monitor);
    }

    private RepositoryToMonitor insertMonitor(long workspaceId, String nameWithOwner, long nativeId) {
        try {
            return monitorCommands.add(workspaceId, nameWithOwner, nativeId);
        } catch (DataIntegrityViolationException exception) {
            if (DataIntegrityViolationConstraints.hasName(exception, "uk_repository_to_monitor_workspace_name")) {
                throw new RepositoryAlreadyMonitoredException(nameWithOwner, exception);
            }
            throw exception;
        }
    }

    /**
     * The repository at {@code nameWithOwner} as its provider reports it to this workspace's own credential. A mirror
     * another workspace stored on the same origin is shared data, never this workspace's authority to read it.
     */
    private RepositoryIdentityLookup.RepositoryIdentity confirmWithOwnCredential(
            IntegrationKind kind, Workspace workspace, String nameWithOwner) {
        RepositoryIdentityLookup lookup = repositoryIdentityLookups.get(kind);
        if (lookup == null) throw new RepositoryProviderNotConnectedException(workspace.getWorkspaceSlug());
        return lookup.resolve(workspace.getId(), nameWithOwner)
                .filter(reported -> nameWithOwner.equalsIgnoreCase(reported.fullPath()))
                .orElseThrow(() -> new EntityNotFoundException("Repository", nameWithOwner));
    }

    /**
     * The workspace's active {@code kind} connection once its lifecycle lock is held, read again rather than taken
     * from this persistence context; refused unless it is still the connection that answered the provider lookup.
     */
    private Connection lockAnsweringConnection(
            long workspaceId, IntegrationKind kind, long answeringConnectionId, String slug) {
        return connectionCommands
                .holdAnsweringConnection(workspaceId, kind, answeringConnectionId)
                .orElseThrow(() -> new RepositoryProviderNotConnectedException(slug));
    }

    private Optional<Long> sourceProviderId(IntegrationKind kind, ConnectionConfig config) {
        return PersonSourceNamespace.from(kind.name(), SourceProviderNamespaces.configuredUrl(config))
                .flatMap(namespace -> identityProviders.findId(IdentityProviderType.from(kind), namespace.serverUrl()));
    }
}
