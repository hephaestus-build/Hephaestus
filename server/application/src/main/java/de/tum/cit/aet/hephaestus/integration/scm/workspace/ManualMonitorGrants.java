package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.event.RepositoryMonitoringChangedEvent;
import de.tum.cit.aet.hephaestus.core.exception.DataIntegrityViolationConstraints;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionCommands;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryIdentityLookup;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.RepositoryMonitorCommands;
import de.tum.cit.aet.hephaestus.workspace.spi.RepositoryMonitorSources;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/** The steps that every provider's manual Add shares: ask with the workspace's own credential, lock, and grant. */
@Component
@WorkspaceAgnostic("Coordinates workspace repository mapping grants against their own provider authority")
class ManualMonitorGrants {
    private final RepositoryMonitorCommands monitorCommands;
    private final RepositoryMonitorSources monitorSources;
    private final ConnectionCommands connectionCommands;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<IntegrationKind, RepositoryIdentityLookup> repositoryIdentityLookups;

    ManualMonitorGrants(
            RepositoryMonitorCommands monitorCommands,
            RepositoryMonitorSources monitorSources,
            ConnectionCommands connectionCommands,
            ApplicationEventPublisher eventPublisher,
            List<RepositoryIdentityLookup> repositoryIdentityLookupList) {
        this.monitorCommands = monitorCommands;
        this.monitorSources = monitorSources;
        this.connectionCommands = connectionCommands;
        this.eventPublisher = eventPublisher;
        Map<IntegrationKind, RepositoryIdentityLookup> identityLookupMap = new EnumMap<>(IntegrationKind.class);
        for (RepositoryIdentityLookup lookup : repositoryIdentityLookupList) {
            identityLookupMap.put(lookup.kind(), lookup);
        }
        this.repositoryIdentityLookups = identityLookupMap;
    }

    /**
     * The repository at {@code nameWithOwner} as its provider reports it to this workspace's own credential. A mirror
     * another workspace stored on the same origin is shared data, never this workspace's authority to read it.
     */
    RepositoryIdentityLookup.RepositoryIdentity confirmWithOwnCredential(
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
    Connection lockAnsweringConnection(
            long workspaceId, IntegrationKind kind, long answeringConnectionId, String slug) {
        return connectionCommands
                .holdAnsweringConnection(workspaceId, kind, answeringConnectionId)
                .orElseThrow(() -> new RepositoryProviderNotConnectedException(slug));
    }

    /** Adds the monitor inside the caller's transaction and announces what it reads. */
    RepositoryToMonitor grant(long workspaceId, String nameWithOwner, long nativeId) {
        RepositoryToMonitor saved;
        try {
            saved = monitorCommands.add(workspaceId, nameWithOwner, nativeId);
        } catch (DataIntegrityViolationException exception) {
            if (DataIntegrityViolationConstraints.hasName(exception, "uk_repository_to_monitor_workspace_name")) {
                throw new RepositoryAlreadyMonitoredException(nameWithOwner, exception);
            }
            throw exception;
        }
        eventPublisher.publishEvent(
                new RepositoryMonitoringChangedEvent(workspaceId, monitorSources.sourceRepositoryIds(saved.getId())));
        return saved;
    }
}
