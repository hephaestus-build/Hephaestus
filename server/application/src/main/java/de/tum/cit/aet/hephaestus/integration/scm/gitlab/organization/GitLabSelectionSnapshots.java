package de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionCommands;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabSelectedMembershipProjection.Outcome;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceLookup;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceProviderAccounts;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRepositoryMonitors;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Reads what the members of a workspace that follows only its selected GitLab projects depend on, before GitLab is
 * asked: the workspace's selection, its active GitLab connection and its monitors ({@link #capture}). It checks later,
 * inside the transaction that changes members, that none of it changed while GitLab was read ({@link #unchanged}).
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
class GitLabSelectionSnapshots {

    private static final Logger log = LoggerFactory.getLogger(GitLabSelectionSnapshots.class);

    record Monitored(long nativeId, String path) {}

    record Snapshot(
            long workspaceId,
            long connectionId,
            Long connectionVersion,
            @Nullable String serverUrl,
            long providerId,
            String group,
            @Nullable Long organizationId,
            Map<Long, Monitored> monitors) {}

    /** The snapshot GitLab is read for, or, without one, the outcome that asks GitLab nothing. */
    record Capture(Outcome outcome, @Nullable Snapshot snapshot) {
        static Capture skip(Outcome outcome) {
            return new Capture(outcome, null);
        }

        static Capture of(Snapshot snapshot) {
            return new Capture(Outcome.APPLIED, snapshot);
        }
    }

    private final WorkspaceLookup workspaceLookup;
    private final WorkspaceProviderAccounts providerAccounts;
    private final WorkspaceRepositoryMonitors monitors;
    private final ConnectionCommands connections;
    private final ConnectionQueries connectionQueries;
    private final WorkspaceActorSelector actorSelector;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    GitLabSelectionSnapshots(
            WorkspaceLookup workspaceLookup,
            WorkspaceProviderAccounts providerAccounts,
            WorkspaceRepositoryMonitors monitors,
            ConnectionCommands connections,
            ConnectionQueries connectionQueries,
            WorkspaceActorSelector actorSelector) {
        this.workspaceLookup = workspaceLookup;
        this.providerAccounts = providerAccounts;
        this.monitors = monitors;
        this.connections = connections;
        this.connectionQueries = connectionQueries;
        this.actorSelector = actorSelector;
    }

    /**
     * The workspace's selection, connection and monitors, read in the caller's transaction, or the outcome that asks
     * GitLab nothing.
     */
    Capture capture(long workspaceId) {
        WorkspaceProviderAccounts.ProviderAccount policy =
                providerAccounts.providerAccount(workspaceId).orElse(null);
        ConnectionQueries.ConnectionSnapshot connection = connectionQueries
                .findActive(workspaceId, IntegrationKind.GITLAB)
                .orElse(null);
        Workspace workspace = workspaceLookup.find(workspaceId).orElse(null);
        if (policy == null
                || policy.repositorySelection() != RepositorySelection.SELECTED
                || connection == null
                || workspace == null) {
            return Capture.skip(Outcome.NOT_SELECTED);
        }
        EntityManager em = Objects.requireNonNull(entityManager);
        em.refresh(workspace);
        if (!(connection.config() instanceof GitLabConfig config)) {
            return Capture.skip(Outcome.NOT_SELECTED);
        }
        Optional<Long> providerId = actorSelector.connectedProviderId(workspaceId);
        Optional<Map<Long, Monitored>> monitored = monitored(workspaceId, policy.accountLogin());
        if (providerId.isEmpty() || monitored.isEmpty()) {
            log.warn("Skipped selected membership changes: reason=unresolvedMonitor, workspaceId={}", workspaceId);
            return Capture.skip(Outcome.UNKNOWN);
        }
        return Capture.of(new Snapshot(
                workspaceId,
                connection.id(),
                connection.version(),
                config.serverUrl(),
                providerId.get(),
                policy.accountLogin(),
                workspace.getOrganizationId(),
                monitored.get()));
    }

    /**
     * The workspace's monitors by id, each with the project id it names and its path; empty when a monitor names no
     * project id or a path outside the connected group, so it cannot say which project it selected.
     */
    private Optional<Map<Long, Monitored>> monitored(long workspaceId, String group) {
        EntityManager em = Objects.requireNonNull(entityManager);
        String root = group.toLowerCase(Locale.ROOT) + "/";
        Map<Long, Monitored> monitored = new HashMap<>();
        for (RepositoryToMonitor monitor : monitors.monitors(workspaceId)) {
            em.refresh(monitor);
            Long nativeId = monitor.getNativeId();
            String path = monitor.getNameWithOwner();
            if (nativeId == null || !path.toLowerCase(Locale.ROOT).startsWith(root)) {
                return Optional.empty();
            }
            monitored.put(monitor.getId(), new Monitored(nativeId, path));
        }
        return Optional.of(Map.copyOf(monitored));
    }

    /** Whether the connection, the selection, the group link and every monitor are still as the snapshot read them. */
    boolean unchanged(Snapshot snapshot, Workspace workspace) {
        long workspaceId = snapshot.workspaceId();
        EntityManager em = Objects.requireNonNull(entityManager);
        em.refresh(workspace);
        Connection connection = connections
                .findHeldConnection(workspaceId, snapshot.connectionId())
                .orElse(null);
        if (connection == null) {
            return false;
        }
        em.refresh(connection);
        return connection.getState() == IntegrationState.ACTIVE
                && Objects.equals(connection.getVersion(), snapshot.connectionVersion())
                && connection.getConfig() instanceof GitLabConfig config
                && Objects.equals(config.serverUrl(), snapshot.serverUrl())
                && connectionQueries
                        .findActive(workspaceId, IntegrationKind.GITLAB)
                        .filter(active -> active.id() == snapshot.connectionId())
                        .isPresent()
                && providerAccounts
                        .providerAccount(workspaceId)
                        .filter(policy -> policy.repositorySelection() == RepositorySelection.SELECTED
                                && policy.accountLogin().equals(snapshot.group()))
                        .isPresent()
                && actorSelector
                        .connectedProviderId(workspaceId)
                        .filter(providerId -> providerId == snapshot.providerId())
                        .isPresent()
                && Objects.equals(workspace.getOrganizationId(), snapshot.organizationId())
                && monitored(workspaceId, snapshot.group())
                        .filter(snapshot.monitors()::equals)
                        .isPresent();
    }
}
