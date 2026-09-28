package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.consumer.RouteAdmission;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabDescendantGroupResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabProjectResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.GitLabProjectSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabRouteCredential;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitlabSubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import io.nats.client.Message;
import io.nats.client.impl.Headers;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Admits a delivery a connection's own hook authenticated, once it is durable: the connection must still be the active
 * GitLab connection of the workspace whose consumer received it, on the configured instance, and every claim the
 * receiver verified must still match it. While the delivery is handled, {@link #current()} names that route, so the
 * GitLab handlers act for the signed connection instead of inferring a workspace from the payload path.
 *
 * <p>The token proves which hook sent a delivery, not what its body says: the group's owner can shape a body. So
 * before any handler runs, this asks GitLab, with the connection's own credential, for everything the body would make
 * Hephaestus write to rows other workspaces share: the project it names, the users it names, the membership a member
 * event changes and the subgroup a subgroup event names. Handlers write what GitLab reported, never the body's copy.
 * Those reads happen outside any transaction; every write happens in a transaction that first holds the connection's
 * lifecycle lock ({@link #holdActive}), so a disconnect either happens before and nothing is written, or after.
 *
 * <p>A failed lookup, in the database or at GitLab, throws and the delivery is redelivered; a route, project or group
 * that does not match is acknowledged unhandled.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabRouteAdmission implements RouteAdmission {

    private static final Logger log = LoggerFactory.getLogger(GitLabRouteAdmission.class);
    private static final String SUBJECT_PREFIX = "gitlab." + GitlabSubjectKeyDeriver.CONNECTION_TOKEN + ".";
    private static final ThreadLocal<@Nullable Delivery> CURRENT = new ThreadLocal<>();

    /** The connection a delivery was admitted for, the GitLab instance it is on and the group its hook covers. */
    public record AdmittedRoute(long connectionId, long workspaceId, long providerId, long groupId, String groupPath) {

        /** Whether {@code path} is the connected group or lies inside it. GitLab paths compare case-insensitively. */
        public boolean contains(@Nullable String path) {
            if (path == null || path.isBlank()) {
                return false;
            }
            String candidate = path.toLowerCase(Locale.ROOT);
            String group = groupPath.toLowerCase(Locale.ROOT);
            return candidate.equals(group) || candidate.startsWith(group + "/");
        }
    }

    /**
     * What GitLab reports about one user's membership of the group a member event names: one entry per relation GitLab
     * counts, none when the user is not a member. For the connected group itself inherited access counts, as it does in
     * GitLab; for a subgroup only direct membership does, as in the team sync.
     */
    public record ReportedMembership(long groupId, List<GitLabGroupMemberResponse> members) {}

    /** What GitLab reported for the delivery being handled, read before its handler ran. */
    private record Delivery(
            AdmittedRoute route,
            Map<Long, GitLabUserLookup> users,
            @Nullable ReportedMembership membership,
            @Nullable GitLabDescendantGroupResponse group) {}

    private final ConnectionRepository connectionRepository;
    private final IdentityProviderRepository identityProviderRepository;
    private final RepositoryRepository repositoryRepository;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final WorkspaceScopeFilter workspaceScopeFilter;
    private final GitLabRepositoryMonitors repositoryMonitors;
    private final GitLabProjectSyncService projectSyncService;
    private final GitLabTeamSyncService teamSyncService;
    private final GitLabUserService userService;
    private final SyncTargetProvider syncTargetProvider;
    private final GitLabProperties gitLabProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    public GitLabRouteAdmission(
            ConnectionRepository connectionRepository,
            IdentityProviderRepository identityProviderRepository,
            RepositoryRepository repositoryRepository,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            WorkspaceScopeFilter workspaceScopeFilter,
            GitLabRepositoryMonitors repositoryMonitors,
            GitLabProjectSyncService projectSyncService,
            GitLabTeamSyncService teamSyncService,
            GitLabUserService userService,
            SyncTargetProvider syncTargetProvider,
            GitLabProperties gitLabProperties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.connectionRepository = connectionRepository;
        this.identityProviderRepository = identityProviderRepository;
        this.repositoryRepository = repositoryRepository;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.repositoryMonitors = repositoryMonitors;
        this.projectSyncService = projectSyncService;
        this.teamSyncService = teamSyncService;
        this.userService = userService;
        this.syncTargetProvider = syncTargetProvider;
        this.gitLabProperties = gitLabProperties;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** The route the delivery being handled on this thread was admitted for, when it came through one. */
    public static Optional<AdmittedRoute> current() {
        return Optional.ofNullable(CURRENT.get()).map(Delivery::route);
    }

    /** The profile GitLab reported for user {@code nativeId} named by the delivery being handled on this thread. */
    public static Optional<GitLabUserLookup> reportedUser(long nativeId) {
        return Optional.ofNullable(CURRENT.get())
                .map(delivery -> delivery.users().get(nativeId));
    }

    /** The membership GitLab reported for the member event being handled on this thread. */
    public static Optional<ReportedMembership> reportedMembership() {
        return Optional.ofNullable(CURRENT.get()).map(Delivery::membership);
    }

    /** The group GitLab reported for the subgroup event being handled on this thread, when it still reports one. */
    public static Optional<GitLabDescendantGroupResponse> reportedGroup() {
        return Optional.ofNullable(CURRENT.get()).map(Delivery::group);
    }

    @Override
    public boolean owns(String subject) {
        return subject.startsWith(SUBJECT_PREFIX);
    }

    @Override
    public boolean admit(@Nullable Long scopeId, Message msg, Runnable handling) {
        Optional<GitLabRouteCredential.Route> claims = claims(msg);
        if (claims.isEmpty()) {
            log.warn("Skipped GitLab delivery: reason=malformedRoute");
            return false;
        }
        AdmittedRoute route = transaction.execute(status -> resolve(claims.get(), scopeId));
        if (route == null) {
            return false;
        }
        JsonNode payload = objectMapper.readTree(msg.getData());
        if (!admitProject(route, payload)) {
            return false;
        }
        String eventName = text(payload, "event_name");
        ReportedMembership membership = null;
        if (eventName != null && eventName.startsWith("user_") && eventName.endsWith("_group")) {
            membership = reportMembership(route, payload);
            if (membership == null) {
                return false;
            }
        }
        GitLabDescendantGroupResponse group = null;
        Long groupId = number(payload, "group_id");
        if (eventName != null && eventName.startsWith("subgroup_") && groupId != null) {
            group = teamSyncService.fetchGroup(route.workspaceId(), groupId).orElse(null);
        }
        Map<Long, GitLabUserLookup> users = userService.fetchCanonicalUsers(route.workspaceId(), userIds(payload));
        CURRENT.set(new Delivery(route, users, membership, group));
        try {
            handling.run();
        } finally {
            CURRENT.remove();
        }
        return true;
    }

    /**
     * Locks the admitted connection for the rest of the caller's write transaction, the way every connection lifecycle
     * change does, and confirms everything the route was admitted for still holds. A handler calls this before its
     * first write, so a disconnect either finishes first and the handler writes nothing, or waits until it is done.
     */
    public boolean holdActive(AdmittedRoute route) {
        connectionRepository.acquireLifecycleLock(route.connectionId(), route.workspaceId());
        boolean active = connectionRepository
                .findByIdAndWorkspaceId(route.connectionId(), route.workspaceId())
                .filter(connection -> mismatch(connection, route.groupId(), route.groupPath()) == null)
                .isPresent();
        if (!active) {
            log.info("Skipped GitLab delivery: reason=connectionInactive, connectionId={}", route.connectionId());
        }
        return active;
    }

    /**
     * Whether the admitted workspace may handle work on {@code repository}. Called inside the handler's write
     * transaction: the connection is held active, the repository must be on the connection's GitLab instance and
     * inside its group, and the workspace must monitor it now. A monitor is only ever created where GitLab has just
     * reported the project ({@link #monitorIfAllowed}), so a monitor removed meanwhile, say by a transfer out of the
     * group, is never recreated from the shared row's old path.
     */
    public boolean admitRepository(AdmittedRoute route, Repository repository) {
        if (!holdActive(route)) {
            return false;
        }
        String path = repository.getNameWithOwner();
        if (!Objects.equals(repository.getProvider().getId(), route.providerId()) || !route.contains(path)) {
            log.warn(
                    "Skipped GitLab delivery: reason=outsideConnectedGroup, connectionId={}, repoId={}",
                    route.connectionId(),
                    repository.getId());
            return false;
        }
        return repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(route.workspaceId(), path)
                && workspaceScopeFilter.isRepositoryAllowed(workspace(route), path);
    }

    /**
     * Brings this workspace's monitor of {@code repository}, which GitLab has just reported inside the connected group,
     * in line with it, and monitors it when the policy allows and no monitor exists; called in the transaction that
     * stored it, holding the lifecycle lock.
     */
    private void monitorIfAllowed(AdmittedRoute route, Repository repository) {
        repositoryMonitors.monitorAllowed(workspace(route), List.of(repository));
    }

    /**
     * This workspace's monitors of project {@code nativeId}: those stored with that id, or else one stored without an
     * id at {@code knownPath}, the path of the stored row for that very project, inside the connected group. A monitor
     * without an id anywhere else is not provably this project's and is left unchanged.
     */
    private List<RepositoryToMonitor> ownMonitors(AdmittedRoute route, long nativeId, @Nullable String knownPath) {
        List<RepositoryToMonitor> byId =
                repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(route.workspaceId(), nativeId);
        if (!byId.isEmpty() || knownPath == null || !route.contains(knownPath)) {
            return byId;
        }
        return repositoryToMonitorRepository
                .findByWorkspaceIdAndNameWithOwner(route.workspaceId(), knownPath)
                .filter(monitor -> monitor.getNativeId() == null)
                .map(monitor -> List.of(monitor))
                .orElse(List.of());
    }

    private Workspace workspace(AdmittedRoute route) {
        return connectionRepository
                .findByIdAndWorkspaceId(route.connectionId(), route.workspaceId())
                .orElseThrow()
                .getWorkspace();
    }

    private @Nullable AdmittedRoute resolve(GitLabRouteCredential.Route claims, @Nullable Long scopeId) {
        long connectionId = claims.connectionId();
        long workspaceId = claims.workspaceId();
        if (scopeId != null && scopeId != workspaceId) {
            log.warn("Skipped GitLab delivery: reason=workspaceMismatch, connectionId={}", connectionId);
            return null;
        }
        connectionRepository.acquireLifecycleLock(connectionId, workspaceId);
        Connection connection = connectionRepository
                .findByIdAndWorkspaceId(connectionId, workspaceId)
                .orElse(null);
        if (connection == null) {
            log.info("Skipped GitLab delivery: reason=connectionInactive, connectionId={}", connectionId);
            return null;
        }
        String mismatch =
                !ScmOrigin.of(gitLabProperties.defaultServerUrl()).equals(Optional.of(claims.providerOrigin()))
                        ? "instanceMismatch"
                        : mismatch(connection, claims.groupId(), claims.groupPath());
        if (mismatch != null) {
            log.warn("Skipped GitLab delivery: reason={}, connectionId={}", mismatch, connectionId);
            return null;
        }
        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, gitLabProperties.defaultServerUrl())
                .orElseThrow(() -> new IllegalStateException("GitLab identity provider is not provisioned"));
        return new AdmittedRoute(
                connectionId,
                workspaceId,
                Objects.requireNonNull(provider.getId()),
                claims.groupId(),
                Objects.requireNonNull(connection.getWorkspace().getAccountLogin()));
    }

    /** Why {@code connection} is no longer the active GitLab connection of group {@code groupId} at {@code groupPath}. */
    private @Nullable String mismatch(Connection connection, long groupId, String groupPath) {
        if (connection.getKind() != IntegrationKind.GITLAB
                || connection.getState() != IntegrationState.ACTIVE
                || !(connection.getConfig() instanceof ConnectionConfig.GitLabConfig config)) {
            return "connectionInactive";
        }
        Workspace workspace = connection.getWorkspace();
        if (workspace.getStatus() != Workspace.WorkspaceStatus.ACTIVE
                || !workspaceScopeFilter.isWorkspaceAllowed(workspace)) {
            return "workspaceInactive";
        }
        Optional<String> configured = ScmOrigin.of(gitLabProperties.defaultServerUrl());
        if (configured.isEmpty() || !ScmOrigin.of(config.serverUrl()).equals(configured)) {
            return "instanceMismatch";
        }
        String accountLogin = workspace.getAccountLogin();
        if (!Objects.equals(config.gitlabGroupId(), groupId)
                || accountLogin == null
                || !accountLogin.equalsIgnoreCase(groupPath)) {
            return "groupMismatch";
        }
        return null;
    }

    /**
     * Whether the project {@code payload} names, if any, is one GitLab reports inside the connected group, after storing
     * what GitLab reported. A created, renamed, moved or deleted project is looked up by its id, so a stale or invented
     * path cannot move it. A project GitLab reports elsewhere, or not at all, only stops being monitored by this
     * workspace: its shared row is left as last stored, since not being reported to one connection proves neither a
     * deletion nor where it went.
     */
    private boolean admitProject(AdmittedRoute route, JsonNode payload) {
        String eventName = text(payload, "event_name");
        if (eventName != null && eventName.startsWith("project_")) {
            Long nativeId = number(payload, "project_id");
            return nativeId != null && admitProjectEvent(route, nativeId);
        }
        JsonNode project = payload.path("project");
        String path = text(project, "path_with_namespace");
        if (path == null) {
            return true;
        }
        Long nativeId = number(project, "id");
        Long projectId = nativeId != null ? nativeId : number(payload, "project_id");
        if (!route.contains(path)) {
            log.warn("Skipped GitLab delivery: reason=outsideConnectedGroup, path={}", sanitizeForLog(path));
            return false;
        }
        // A stored project is trusted without asking GitLab only while this workspace monitors it: the shared row may
        // still hold a path its project was moved away from, and monitoring it again needs GitLab's word.
        Boolean monitoredKnown = transaction.execute(status ->
                repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(route.workspaceId(), path)
                        && repositoryRepository
                                .findByNameWithOwnerAndProviderId(path, route.providerId())
                                .filter(known -> projectId == null || projectId.equals(known.getNativeId()))
                                .isPresent());
        if (Boolean.TRUE.equals(monitoredKnown)) {
            return true;
        }
        GitLabProjectResponse reported =
                projectSyncService.fetchProject(route.workspaceId(), path).orElse(null);
        if (reported == null
                || !route.contains(reported.fullPath())
                || (projectId != null && projectId != extractId(reported))) {
            log.info("Skipped GitLab delivery: reason=projectNotReported, connectionId={}", route.connectionId());
            return false;
        }
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!holdActive(route)) {
                return false;
            }
            Optional<Repository> stored = projectSyncService.persistProject(reported);
            stored.ifPresent(repository -> monitorIfAllowed(route, repository));
            return stored.isPresent();
        }));
    }

    private boolean admitProjectEvent(AdmittedRoute route, long nativeId) {
        GitLabProjectResponse reported = projectSyncService
                .fetchProjectById(route.workspaceId(), nativeId)
                .orElse(null);
        boolean inside = reported != null && route.contains(reported.fullPath());
        Boolean applied = transaction.execute(status -> {
            if (!holdActive(route)) {
                return false;
            }
            // The shared row may already carry another workspace's view of the move, so this workspace's monitor is
            // found by the project's id, not by the row's current path.
            String knownPath = repositoryRepository
                    .findByNativeIdAndProviderId(nativeId, route.providerId())
                    .map(Repository::getNameWithOwner)
                    .orElse(null);
            List<RepositoryToMonitor> own = ownMonitors(route, nativeId, knownPath);
            Repository stored = inside && reported != null
                    ? projectSyncService.persistProject(reported).orElse(null)
                    : null;
            if (!inside) {
                own.forEach(monitor -> syncTargetProvider.removeSyncTarget(monitor.getId()));
            }
            if (stored == null) {
                return false;
            }
            // A monitor from before native ids learns this project's id where it is; the shared monitor boundary
            // then merges any other monitor of the project into the first and moves that one to the reported path.
            own.stream()
                    .filter(monitor -> monitor.getNativeId() == null)
                    .forEach(monitor -> syncTargetProvider.reconcileSyncTargetIdentity(
                            monitor.getId(), nativeId, monitor.getNameWithOwner()));
            monitorIfAllowed(route, stored);
            return true;
        });
        if (!inside) {
            log.info(
                    "Skipped GitLab project event: reason={}, projectId={}",
                    reported == null ? "projectNotReported" : "outsideConnectedGroup",
                    nativeId);
        }
        return Boolean.TRUE.equals(applied);
    }

    /**
     * The membership GitLab reports for the user and group a member event names, or {@code null} when GitLab reports no
     * such group inside the connected one, which says nothing about the membership.
     */
    private @Nullable ReportedMembership reportMembership(AdmittedRoute route, JsonNode payload) {
        Long groupId = number(payload, "group_id");
        Long userId = number(payload, "user_id");
        if (groupId == null || userId == null) {
            return null;
        }
        String groupPath;
        boolean connectedGroup = groupId == route.groupId();
        if (connectedGroup) {
            groupPath = route.groupPath();
        } else {
            GitLabDescendantGroupResponse group =
                    teamSyncService.fetchGroup(route.workspaceId(), groupId).orElse(null);
            if (group == null || !route.contains(group.fullPath())) {
                log.info("Skipped GitLab member event: reason=groupNotReported, groupId={}", groupId);
                return null;
            }
            groupPath = group.fullPath();
        }
        return teamSyncService
                .fetchMembership(route.workspaceId(), groupPath, groupId, userId, connectedGroup)
                .map(members -> new ReportedMembership(groupId, members))
                .orElse(null);
    }

    /** The users whose profiles a handler may store from this payload. */
    private static List<Long> userIds(JsonNode payload) {
        List<Long> ids = new ArrayList<>();
        addId(ids, payload.path("user"), "id");
        addId(ids, payload, "user_id");
        for (String list : List.of("assignees", "reviewers")) {
            payload.path(list).forEach(user -> addId(ids, user, "id"));
        }
        return ids;
    }

    private static void addId(List<Long> ids, JsonNode node, String field) {
        Long id = number(node, field);
        if (id != null) {
            ids.add(id);
        }
    }

    private static long extractId(GitLabProjectResponse project) {
        String id = project.id();
        return Long.parseLong(id.substring(id.lastIndexOf('/') + 1));
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    private static @Nullable Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() && value.canConvertToLong() ? value.asLong() : null;
    }

    /** The route the receiver verified, from the subject it published on and the headers it wrote. */
    private static Optional<GitLabRouteCredential.Route> claims(Message msg) {
        String subject = msg.getSubject();
        Headers headers = msg.getHeaders();
        if (subject == null || headers == null) {
            return Optional.empty();
        }
        String[] tokens = subject.split("\\.");
        try {
            long connectionId = Long.parseLong(tokens[2]);
            long workspaceId =
                    Long.parseLong(Objects.requireNonNull(headers.getFirst(GitLabRouteCredential.HEADER_WORKSPACE)));
            long groupId =
                    Long.parseLong(Objects.requireNonNull(headers.getFirst(GitLabRouteCredential.HEADER_GROUP_ID)));
            String origin = Objects.requireNonNull(headers.getFirst(GitLabRouteCredential.HEADER_ORIGIN));
            String groupPath = Objects.requireNonNull(headers.getFirst(GitLabRouteCredential.HEADER_GROUP_PATH));
            return Optional.of(new GitLabRouteCredential.Route(connectionId, workspaceId, origin, groupId, groupPath));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
