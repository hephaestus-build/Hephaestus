package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Stands in for {@link GitLabRouteAdmission#admit}: runs a handler call as a delivery admitted on a connection's route.
 * GitLab handlers write nothing without one.
 */
public final class GitLabRouteTestFixtures {

    private GitLabRouteTestFixtures() {}

    /** Runs {@code handling} as a delivery admitted on {@code route}, with what GitLab reported for it. */
    public static void handle(
            GitLabRouteAdmission.AdmittedRoute route,
            Map<Long, GitLabUserLookup> users,
            GitLabProjectAdmission.@Nullable ProjectRead project,
            Runnable handling) {
        GitLabRouteAdmission.handleAdmitted(route, users, null, null, project, handling);
    }

    /**
     * The profiles GitLab reports for the users a payload names, where admission reads them: {@code user},
     * {@code assignees} and {@code reviewers}. Each mirrors the payload's own profile.
     */
    public static Map<Long, GitLabUserLookup> reportedUsers(JsonNode payload, String serverUrl) {
        Map<Long, GitLabUserLookup> users = new LinkedHashMap<>();
        addUser(users, payload.path("user"), serverUrl);
        for (String list : List.of("assignees", "reviewers")) {
            payload.path(list).forEach(user -> addUser(users, user, serverUrl));
        }
        return users;
    }

    private static void addUser(Map<Long, GitLabUserLookup> users, JsonNode user, String serverUrl) {
        if (!user.path("id").isNumber() || !user.path("username").isString()) {
            return;
        }
        long id = user.path("id").asLong();
        String username = user.path("username").asString();
        users.put(
                id,
                new GitLabUserLookup(
                        "gid://gitlab/User/" + id,
                        username,
                        text(user, "name"),
                        text(user, "avatar_url"),
                        serverUrl + "/" + username,
                        text(user, "email"),
                        null));
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }

    /**
     * Delivers handler calls for a GitLab workspace as its connection's route would, in an integration test: the route
     * names the workspace's active connection and group, the project read is what admission captures for the
     * repository, and GitLab reports each user the event names with the event's own profile.
     */
    public static final class Deliveries {

        /** The group id a test connection is given when it has none. */
        public static final long GROUP_ID = 4242L;

        private final ConnectionRepository connections;
        private final ConnectionService connectionService;
        private final GitLabProjectAdmission admission;
        private final ObjectMapper objectMapper;
        private final TransactionTemplate transactions;
        private final String serverUrl;

        public Deliveries(
                ConnectionRepository connections,
                ConnectionService connectionService,
                GitLabProjectAdmission admission,
                ObjectMapper objectMapper,
                TransactionTemplate transactions,
                String serverUrl) {
            this.connections = connections;
            this.connectionService = connectionService;
            this.admission = admission;
            this.objectMapper = objectMapper;
            this.transactions = transactions;
            this.serverUrl = serverUrl;
        }

        /** Runs {@code handling} as the delivery of {@code event} for {@code repository} on {@code workspace}'s route. */
        public void handle(Workspace workspace, Repository repository, Object event, Runnable handling) {
            call(workspace, repository, event, () -> {
                handling.run();
                return null;
            });
        }

        /** {@link #handle} for a handler call with a result. */
        public <T> @Nullable T call(
                Workspace workspace, Repository repository, Object event, Supplier<@Nullable T> handling) {
            GitLabRouteAdmission.AdmittedRoute route = route(workspace, repository);
            GitLabProjectAdmission.ProjectRead project = transactions.execute(status -> admission
                    .captureConnection(route.workspaceId(), route.providerId())
                    .flatMap(read -> admission.capture(read, repository))
                    .orElse(null));
            AtomicReference<@Nullable T> result = new AtomicReference<>();
            GitLabRouteTestFixtures.handle(
                    route,
                    reportedUsers(objectMapper.valueToTree(event), serverUrl),
                    project,
                    () -> result.set(handling.get()));
            return result.get();
        }

        /** The route of {@code workspace}'s active GitLab connection, given a group id if it has none. */
        public GitLabRouteAdmission.AdmittedRoute route(Workspace workspace, Repository repository) {
            long workspaceId = Objects.requireNonNull(workspace.getId());
            var connection =
                    connections.findActive(workspaceId, IntegrationKind.GITLAB).orElseThrow();
            var config = (ConnectionConfig.GitLabConfig) connection.getConfig();
            Long groupId = config.gitlabGroupId();
            if (groupId == null) {
                connectionService.updateConfig(
                        workspaceId,
                        IntegrationKind.GITLAB,
                        current -> ((ConnectionConfig.GitLabConfig) current).withGitlabGroupId(GROUP_ID));
                groupId = GROUP_ID;
            }
            return new GitLabRouteAdmission.AdmittedRoute(
                    Objects.requireNonNull(connection.getId()),
                    workspaceId,
                    Objects.requireNonNull(repository.getProvider().getId()),
                    groupId,
                    Objects.requireNonNull(workspace.getAccountLogin()));
        }
    }
}
