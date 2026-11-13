package de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionCommands;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMemberRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.collaborator.GitLabCollaboratorSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.collaborator.GitLabCollaboratorSyncService.ListedCollaborator;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.collaborator.GitLabCollaboratorSyncService.ProjectMembers;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sets the members of a workspace that follows only the GitLab projects it selected. A person is a member while a
 * selected project grants them access itself ({@link #PROJECT_GRANT_RELATIONS}), or while a group strictly between
 * the connected group and a selected project does ({@link GitLabGroupMemberResponse#TEAM_RELATIONS}). A grant of the
 * connected group or of one of its ancestors alone selects no project. A member is ADMIN only while the connected
 * group's roster holds them as ADMIN, and OWNER stays through {@link MembershipService#syncWorkspaceMembers}.
 *
 * <p>The workspace, its connection and its monitors are read first. GitLab is then asked with the workspace's own
 * credential, outside a transaction. Members change only after every listing is complete, in one short transaction
 * under the connection's lifecycle lock, and only while nothing read first has changed: Stop, Add and a selection
 * change take the same lock. Anything less changes nobody.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabSelectedMembershipProjection {

    private static final Logger log = LoggerFactory.getLogger(GitLabSelectedMembershipProjection.class);

    /** A project's own grants: its members and the members of groups invited to it, not what it inherits. */
    private static final List<String> PROJECT_GRANT_RELATIONS = List.of("DIRECT", "INVITED_GROUPS");

    /** A guest grant on a project does not read its code, as for the project members a team counts. */
    private static final int REPORTER_ACCESS_LEVEL = 20;

    public enum Outcome {
        /** The workspace follows every project of its group, or has no active GitLab connection: nothing changed. */
        NOT_SELECTED,
        /** The workspace's members are what the selected projects and their subgroups grant now. */
        APPLIED,
        /** GitLab or a monitor did not say who is granted access: nothing changed. */
        UNKNOWN,
        /** The connection, the selection or a monitor changed while GitLab was read: nothing changed. */
        STALE
    }

    private record Monitored(long nativeId, String path) {}

    private record Snapshot(
            long workspaceId,
            long connectionId,
            Long connectionVersion,
            @Nullable String serverUrl,
            long providerId,
            String group,
            @Nullable Long organizationId,
            Map<Long, Monitored> monitors) {}

    /** The snapshot GitLab is read for, or, without one, the outcome that asks GitLab nothing. */
    private record Capture(Outcome outcome, @Nullable Snapshot snapshot) {
        static Capture skip(Outcome outcome) {
            return new Capture(outcome, null);
        }

        static Capture of(Snapshot snapshot) {
            return new Capture(Outcome.APPLIED, snapshot);
        }
    }

    private final WorkspaceRepository workspaces;
    private final RepositoryToMonitorRepository monitors;
    private final ConnectionCommands connections;
    private final ConnectionQueries connectionQueries;
    private final WorkspaceActorSelector actorSelector;
    private final OrganizationMembershipRepository roster;
    private final GitLabCollaboratorSyncService collaboratorSync;
    private final GitLabTeamSyncService teamSync;
    private final GitLabUserService userService;
    private final MembershipService membershipService;
    private final TransactionTemplate transactionTemplate;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    public GitLabSelectedMembershipProjection(
            WorkspaceRepository workspaces,
            RepositoryToMonitorRepository monitors,
            ConnectionCommands connections,
            ConnectionQueries connectionQueries,
            WorkspaceActorSelector actorSelector,
            OrganizationMembershipRepository roster,
            GitLabCollaboratorSyncService collaboratorSync,
            GitLabTeamSyncService teamSync,
            GitLabUserService userService,
            MembershipService membershipService,
            TransactionTemplate transactionTemplate) {
        this.workspaces = workspaces;
        this.monitors = monitors;
        this.connections = connections;
        this.connectionQueries = connectionQueries;
        this.actorSelector = actorSelector;
        this.roster = roster;
        this.collaboratorSync = collaboratorSync;
        this.teamSync = teamSync;
        this.userService = userService;
        this.membershipService = membershipService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Sets the members of {@code workspaceId} when it follows only its selected GitLab projects.
     *
     * @return what changed; only {@link Outcome#APPLIED} changed a membership
     */
    public Outcome project(long workspaceId) {
        Capture capture = Objects.requireNonNull(transactionTemplate.execute(status -> capture(workspaceId)));
        Snapshot snapshot = capture.snapshot();
        return snapshot == null ? capture.outcome() : readAndApply(snapshot);
    }

    private Outcome readAndApply(Snapshot snapshot) {
        long workspaceId = snapshot.workspaceId();
        Optional<Collection<GitLabUserLookup>> granted;
        try {
            granted = readGrants(snapshot);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.UNKNOWN;
        } catch (RuntimeException e) {
            log.warn("Failed selected membership read: workspaceId={}", workspaceId, e);
            return Outcome.UNKNOWN;
        }
        if (granted.isEmpty()) {
            log.warn("Skipped selected membership changes: reason=incompleteListing, workspaceId={}", workspaceId);
            return Outcome.UNKNOWN;
        }
        Collection<GitLabUserLookup> people = granted.get();
        return Objects.requireNonNullElse(
                transactionTemplate.execute(status -> apply(snapshot, people, status)), Outcome.UNKNOWN);
    }

    private Capture capture(long workspaceId) {
        WorkspaceRepository.MonitorPolicy policy =
                workspaces.findMonitorPolicy(workspaceId).orElse(null);
        ConnectionQueries.ConnectionSnapshot connection = connectionQueries
                .findActive(workspaceId, IntegrationKind.GITLAB)
                .orElse(null);
        Workspace workspace = workspaces.findById(workspaceId).orElse(null);
        if (policy == null
                || policy.getRepositorySelection() != RepositorySelection.SELECTED
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
        Optional<Map<Long, Monitored>> monitored = monitored(workspaceId, policy.getAccountLogin());
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
                policy.getAccountLogin(),
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
        for (RepositoryToMonitor monitor : monitors.findByWorkspaceId(workspaceId)) {
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

    /**
     * Everyone a selected project or a group between the connected group and a selected project grants access
     * itself, each once; empty when one listing is incomplete or answers for another project.
     */
    private Optional<Collection<GitLabUserLookup>> readGrants(Snapshot snapshot) throws InterruptedException {
        Map<String, GitLabUserLookup> granted = new LinkedHashMap<>();
        Set<String> subgroups = new TreeSet<>();
        for (Monitored project : snapshot.monitors().values()) {
            Optional<ProjectMembers> listing = collaboratorSync.listProjectMembers(
                    snapshot.workspaceId(), project.path(), PROJECT_GRANT_RELATIONS);
            // Only the project the monitor names by id answers for it; a project now at its path lends nothing.
            if (listing.isEmpty() || !Objects.equals(listing.get().projectNativeId(), project.nativeId())) {
                return Optional.empty();
            }
            for (ListedCollaborator member : listing.get().members()) {
                if (member.accessLevel() >= REPORTER_ACCESS_LEVEL) {
                    granted.putIfAbsent(member.globalId(), member.lookup());
                }
            }
            subgroups.addAll(groupsBetween(snapshot.group(), project.path()));
        }
        for (String subgroup : subgroups) {
            Optional<List<GitLabUserLookup>> members = teamSync.listOwnGroupMembers(snapshot.workspaceId(), subgroup);
            if (members.isEmpty()) {
                return Optional.empty();
            }
            for (GitLabUserLookup member : members.get()) {
                granted.putIfAbsent(Objects.requireNonNull(member.globalId()), member);
            }
        }
        return Optional.of(List.copyOf(granted.values()));
    }

    /** Every group strictly between the connected group and the project at {@code path}, outermost first. */
    private static List<String> groupsBetween(String group, String path) {
        List<String> groups = new ArrayList<>();
        for (int end = path.indexOf('/', group.length() + 1); end > 0; end = path.indexOf('/', end + 1)) {
            groups.add(path.substring(0, end));
        }
        return groups;
    }

    private Outcome apply(Snapshot snapshot, Collection<GitLabUserLookup> granted, TransactionStatus status) {
        long workspaceId = snapshot.workspaceId();
        Workspace workspace = workspaces.findByIdForUpdate(workspaceId).orElse(null);
        if (workspace == null) {
            return Outcome.STALE;
        }
        connections.holdLifecycleLock(workspaceId, snapshot.connectionId());
        if (!unchanged(snapshot, workspace)) {
            log.info("Skipped selected membership changes: reason=changedWhileRead, workspaceId={}", workspaceId);
            return Outcome.STALE;
        }
        Map<Long, OrganizationMemberRole> rosterRoles = new HashMap<>();
        Long organizationId = snapshot.organizationId();
        if (organizationId != null) {
            for (OrganizationMembership membership : roster.findByOrganizationId(organizationId)) {
                rosterRoles.put(membership.getUserId(), membership.getRole());
            }
        }
        Map<Long, Membership.WorkspaceRole> desired = new HashMap<>();
        for (GitLabUserLookup person : granted) {
            User user = userService.findOrCreateUser(person, snapshot.providerId());
            if (user == null) {
                status.setRollbackOnly();
                log.warn("Skipped selected membership changes: reason=userNotResolved, workspaceId={}", workspaceId);
                return Outcome.UNKNOWN;
            }
            // A project or subgroup grant administers nothing here; only the connected group's roster does.
            desired.put(
                    user.getId(),
                    rosterRoles.get(user.getId()) == OrganizationMemberRole.ADMIN
                            ? Membership.WorkspaceRole.ADMIN
                            : Membership.WorkspaceRole.MEMBER);
        }
        membershipService.syncWorkspaceMembers(workspace, desired);
        log.info("Synced selected workspace members: workspaceId={}, memberCount={}", workspaceId, desired.size());
        return Outcome.APPLIED;
    }

    /** Whether the connection, the selection, the group link and every monitor are still as the snapshot read them. */
    private boolean unchanged(Snapshot snapshot, Workspace workspace) {
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
                && workspaces
                        .findMonitorPolicy(workspaceId)
                        .filter(policy -> policy.getRepositorySelection() == RepositorySelection.SELECTED
                                && policy.getAccountLogin().equals(snapshot.group()))
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
