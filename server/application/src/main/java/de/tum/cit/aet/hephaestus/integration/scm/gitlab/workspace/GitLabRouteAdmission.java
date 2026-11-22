package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionCommands;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.consumer.RouteAdmission;
import de.tum.cit.aet.hephaestus.integration.core.handler.PayloadParsingException;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabDescendantGroupResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabRouteCredential;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabSubjectKeyDeriver;
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
import tools.jackson.core.JacksonException;
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
 * lifecycle lock ({@link GitLabRouteWrites#holdActive}), so a disconnect either happens before and nothing is
 * written, or after.
 *
 * <p>A failed lookup, in the database or at GitLab, throws and the delivery is redelivered; a route, project or group
 * that does not match is acknowledged unhandled.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabRouteAdmission implements RouteAdmission {

    private static final Logger log = LoggerFactory.getLogger(GitLabRouteAdmission.class);
    private static final String SUBJECT_PREFIX = "gitlab." + GitLabSubjectKeyDeriver.CONNECTION_TOKEN + ".";
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
            @Nullable GitLabDescendantGroupResponse group,
            GitLabProjectAdmission.@Nullable ProjectRead project) {}

    private final ConnectionCommands connectionCommands;
    private final IdentityProviderLookup identityProviders;
    private final RepositoryRepository repositoryRepository;
    private final GitLabTeamSyncService teamSyncService;
    private final GitLabUserService userService;
    private final GitLabProperties gitLabProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;
    private final GitLabProjectAdmission projectAdmission;
    private final GitLabRouteWrites routeWrites;

    public GitLabRouteAdmission(
            ConnectionCommands connectionCommands,
            IdentityProviderLookup identityProviders,
            RepositoryRepository repositoryRepository,
            GitLabTeamSyncService teamSyncService,
            GitLabUserService userService,
            GitLabProperties gitLabProperties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            GitLabProjectAdmission projectAdmission,
            GitLabRouteWrites routeWrites) {
        this.connectionCommands = connectionCommands;
        this.identityProviders = identityProviders;
        this.repositoryRepository = repositoryRepository;
        this.teamSyncService = teamSyncService;
        this.userService = userService;
        this.gitLabProperties = gitLabProperties;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.projectAdmission = projectAdmission;
        this.routeWrites = routeWrites;
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

    /** The project admission stored for the delivery being handled on this thread, when it names one. */
    static GitLabProjectAdmission.@Nullable ProjectRead currentProject() {
        Delivery delivery = CURRENT.get();
        return delivery == null ? null : delivery.project();
    }

    @Override
    public boolean owns(String subject) {
        return subject.startsWith(SUBJECT_PREFIX);
    }

    @Override
    public boolean admit(@Nullable Long scopeId, ProviderEvent event, Runnable handling) {
        Optional<GitLabRouteCredential.Route> claims = claims(event);
        if (claims.isEmpty()) {
            log.warn("Skipped GitLab delivery: reason=malformedRoute");
            return false;
        }
        AdmittedRoute route = transaction.execute(status -> resolve(claims.get(), scopeId));
        if (route == null) {
            return false;
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(event.payload());
        } catch (JacksonException failure) {
            throw new PayloadParsingException("GitLab webhook payload cannot be parsed", failure);
        }
        var read = projectAdmission
                .captureConnection(route.workspaceId(), route.providerId())
                .orElse(null);
        if (read == null
                || read.connectionId() != route.connectionId()
                || !routeWrites.admitProject(route, payload, read)) {
            return false;
        }
        Long projectId = number(payload.path("project"), "id");
        if (projectId == null) projectId = number(payload, "project_id");
        String projectPath = text(payload.path("project"), "path_with_namespace");
        var repository = projectId != null
                ? repositoryRepository.findByNativeIdAndProviderId(projectId, route.providerId())
                : projectPath != null
                        ? repositoryRepository.findByNameWithOwnerAndProviderId(projectPath, route.providerId())
                        : Optional.<Repository>empty();
        var projectRead = repository
                .flatMap(stored -> projectAdmission.capture(read, stored))
                .orElse(null);
        if ((projectId != null || projectPath != null) && projectRead == null) return false;
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
        handleAdmitted(route, users, membership, group, projectRead, handling);
        return true;
    }

    /**
     * Runs {@code handling} as the delivery admitted for {@code route}, with what GitLab reported for it. A handler
     * reads it through {@link #current()} and its siblings and writes nothing without it. {@link #admit} is the only
     * production caller; tests in this package call it to stand in for a delivery that admission confirmed.
     */
    static void handleAdmitted(
            AdmittedRoute route,
            Map<Long, GitLabUserLookup> users,
            @Nullable ReportedMembership membership,
            @Nullable GitLabDescendantGroupResponse group,
            GitLabProjectAdmission.@Nullable ProjectRead project,
            Runnable handling) {
        CURRENT.set(new Delivery(route, users, membership, group, project));
        try {
            handling.run();
        } finally {
            CURRENT.remove();
        }
    }

    private @Nullable AdmittedRoute resolve(GitLabRouteCredential.Route claims, @Nullable Long scopeId) {
        long connectionId = claims.connectionId();
        long workspaceId = claims.workspaceId();
        if (scopeId != null && scopeId != workspaceId) {
            log.warn("Skipped GitLab delivery: reason=workspaceMismatch, connectionId={}", connectionId);
            return null;
        }
        connectionCommands.holdLifecycleLock(workspaceId, connectionId);
        Connection connection =
                connectionCommands.findHeldConnection(workspaceId, connectionId).orElse(null);
        if (connection == null) {
            log.info("Skipped GitLab delivery: reason=connectionInactive, connectionId={}", connectionId);
            return null;
        }
        String mismatch =
                !ScmOrigin.of(gitLabProperties.defaultServerUrl()).equals(Optional.of(claims.providerOrigin()))
                        ? "instanceMismatch"
                        : routeWrites.mismatch(connection, claims.groupId(), claims.groupPath());
        if (mismatch != null) {
            log.warn("Skipped GitLab delivery: reason={}, connectionId={}", mismatch, connectionId);
            return null;
        }
        Long provider = identityProviders
                .findId(IdentityProviderType.GITLAB, gitLabProperties.defaultServerUrl())
                .orElseThrow(() -> new IllegalStateException("GitLab identity provider is not provisioned"));
        return new AdmittedRoute(
                connectionId,
                workspaceId,
                provider,
                claims.groupId(),
                Objects.requireNonNull(connection.getWorkspace().getAccountLogin()));
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

    static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    static @Nullable Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() && value.canConvertToLong() ? value.asLong() : null;
    }

    /** The route the receiver verified, from the subject it published on and the headers it wrote. */
    private static Optional<GitLabRouteCredential.Route> claims(ProviderEvent event) {
        String subject = event.subject();
        String[] tokens = subject.split("\\.", -1);
        try {
            long connectionId = Long.parseLong(tokens[2]);
            long workspaceId =
                    Long.parseLong(Objects.requireNonNull(event.header(GitLabRouteCredential.HEADER_WORKSPACE)));
            long groupId = Long.parseLong(Objects.requireNonNull(event.header(GitLabRouteCredential.HEADER_GROUP_ID)));
            String origin = Objects.requireNonNull(event.header(GitLabRouteCredential.HEADER_ORIGIN));
            String groupPath = Objects.requireNonNull(event.header(GitLabRouteCredential.HEADER_GROUP_PATH));
            return Optional.of(new GitLabRouteCredential.Route(connectionId, workspaceId, origin, groupId, groupPath));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
