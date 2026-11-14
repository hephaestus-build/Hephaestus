package de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventConsumer;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.GitLabProjectSyncService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LiveGitLabTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real GitLab webhook delivery, end to end: GitLab sends its own asynchronous events to the connection route of this
 * server, the receiver stores them in the provider event inbox, and the consumer mirrors the work. Nothing is replayed
 * and no sync runs in between. {@code HEPHAESTUS_LIVE_GITLAB_WEBHOOK_HOST} is this test JVM's address on the GitLab
 * fixture's network.
 *
 * <p>GitLab CE has no group webhooks: they are a Premium and Ultimate feature. Hephaestus registers a group hook, so on
 * CE this proof creates a project hook to the same signed connection route, and it does not prove that registration.
 */
@LiveGitLabTest
class GitLabWebhookDeliveryLiveTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private GitLabProperties gitlab;

    @Autowired
    private GitLabRouteCredential routeCredential;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceLifecycleService lifecycle;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private CredentialBundleConverter converter;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private GitLabProjectSyncService projects;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private ProviderEventConsumer consumer;

    @Autowired
    private JdbcTemplate jdbc;

    @LocalServerPort
    private int port;

    private @Nullable Long workspaceId;
    private @Nullable Long repositoryId;

    @Test
    void shouldMirrorTheIssueAndMergeRequestThatGitLabDeliversToTheConnectionRoute() throws Exception {
        String host = Objects.requireNonNull(System.getenv("HEPHAESTUS_LIVE_GITLAB_WEBHOOK_HOST"));
        try (var fixture = new GitLabWebhookFixture(mapper)) {
            fixture.create();
            assertThat(gitlab.defaultServerUrl()).isEqualTo(fixture.server());
            long connectionId = connect(fixture);
            long repository = Objects.requireNonNull(repositoryId);
            long workspace = Objects.requireNonNull(workspaceId);
            var issued = routeCredential.issue(new GitLabRouteCredential.Route(
                    connectionId, workspace, fixture.server(), fixture.groupId(), fixture.groupPath()));
            String target = host + ":" + port;
            fixture.allowLocal(target);
            fixture.hook(
                    "http://" + target + GitLabConnectionWebhookController.PATH_PREFIX + connectionId + "/"
                            + issued.keyId() + "/" + issued.routeId(),
                    issued.token());
            Instant before = Instant.now();
            String subjectPrefix = "gitlab.?connection." + connectionId + ".";

            String issueTitle = "Webhook proof issue " + fixture.groupPath();
            assertThat(title(repository, "ISSUE", 1))
                    .as("absent before GitLab sends it")
                    .isNull();
            var issue = fixture.issue(issueTitle);
            awaitMirrored(repository, "ISSUE", issue.iid(), issueTitle);
            assertDelivered(fixture, workspace, subjectPrefix + "issue", "Issue Hook", issue.id());

            var mergeRequest = fixture.mergeRequest("Webhook proof merge request");
            String retitled = "Webhook proof merge request, retitled";
            fixture.retitleMergeRequest(mergeRequest.iid(), retitled);
            awaitMirrored(repository, "PULL_REQUEST", mergeRequest.iid(), retitled);
            assertDelivered(
                    fixture, workspace, subjectPrefix + "merge_request", "Merge Request Hook", mergeRequest.id());

            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM sync_job WHERE workspace_id = ? AND created_at >= ?",
                            Long.class,
                            workspace,
                            Timestamp.from(before)))
                    .as("the work arrived by webhook, not by a sync")
                    .isZero();
            assertThat(fixture.version()).isNotBlank();
        }
    }

    /** An active GitLab connection on a new workspace, with the probe project synced and monitored. */
    private long connect(GitLabWebhookFixture fixture) {
        if (providers.findByTypeAndServerUrl(IdentityProviderType.GITLAB, fixture.server()).isEmpty())
            providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, fixture.server()));
        Workspace workspace = WorkspaceTestFixtures.activeWorkspace(fixture.groupPath());
        workspace.setAccountLogin(fixture.groupPath());
        workspace = workspaces.saveAndFlush(workspace);
        workspaceId = workspace.getId();
        var config = new ConnectionConfig.GitLabConfig(fixture.server(), fixture.groupId(), null, Set.of(), null);
        var connection = new Connection(workspace, IntegrationKind.GITLAB, fixture.server(), config);
        connection.setCredentials(new BearerToken(fixture.token(), null), converter);
        connection = connections.saveAndFlush(connection);
        connectionService.transition(
                connection,
                new ConnectionService.TransitionRequest(
                        IntegrationState.ACTIVE,
                        "CONNECT",
                        "SYSTEM",
                        "live-proof",
                        UUID.randomUUID().toString(),
                        "Webhook proof"));
        Repository repository =
                projects.syncProject(workspace.getId(), fixture.projectPath()).orElseThrow();
        repositoryId = repository.getId();
        monitors.saveAndFlush(
                WorkspaceTestFixtures.repositoryMonitor(workspace, repository.getNameWithOwner(), fixture.projectId()));
        return Objects.requireNonNull(connection.getId());
    }

    /** Claims stored events as a running server does, until the mirror holds the work under {@code title}. */
    private void awaitMirrored(long repository, String type, int number, String title) {
        await().atMost(Duration.ofSeconds(120))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    while (consumer.claimAndHandle()) {
                        // Handle what is due before the next look.
                    }
                    assertThat(title(repository, type, number)).isEqualTo(title);
                });
    }

    private @Nullable String title(long repository, String type, int number) {
        return jdbc
                .query(
                        "SELECT title FROM issue WHERE repository_id = ? AND issue_type = ? AND number = ?",
                        (row, index) -> row.getString(1),
                        repository,
                        type,
                        number)
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * The inbox handled a delivery of {@code objectId} from this hook, and GitLab recorded that the receiver accepted
     * it. The hook's UUID ties the stored row to the hook, and the object id ties both sides to the work.
     */
    private void assertDelivered(
            GitLabWebhookFixture fixture, long workspace, String subject, String event, long objectId)
            throws Exception {
        var stored = jdbc.queryForList(
                "SELECT state, headers->>'X-Gitlab-Webhook-UUID' AS hook, convert_from(payload, 'UTF8') AS payload"
                        + " FROM provider_event WHERE workspace_id = ? AND subject = ?",
                workspace,
                subject);
        assertThat(stored).as("a handled inbox row for %s", subject).anySatisfy(row -> {
            assertThat(row.get("state")).isEqualTo("DONE");
            assertThat(mapper.readTree((String) row.get("payload"))
                            .path("object_attributes")
                            .path("id")
                            .asLong())
                    .isEqualTo(objectId);
        });
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode accepted = null;
            for (JsonNode delivery : fixture.deliveries()) {
                if (event.equals(delivery.path("request_headers")
                                .path("X-Gitlab-Event")
                                .asString())
                        && delivery.path("request_data")
                                        .path("object_attributes")
                                        .path("id")
                                        .asLong()
                                == objectId
                        && delivery.path("response_status").asString().startsWith("2")) {
                    accepted = delivery;
                }
            }
            assertThat(accepted)
                    .as("GitLab's record of an accepted %s delivery", event)
                    .isNotNull();
            String hook = Objects.requireNonNull(accepted)
                    .path("request_headers")
                    .path("X-Gitlab-Webhook-UUID")
                    .asString();
            assertThat(stored).anySatisfy(row -> assertThat(row.get("hook")).isEqualTo(hook));
        });
    }

    @AfterEach
    void removeOnlyThisTestsLocalMirror() {
        Long workspace = workspaceId;
        if (workspace != null)
            lifecycle.purgeWorkspace(
                    workspaces.findById(workspace).orElseThrow().getWorkspaceSlug());
        Long repository = repositoryId;
        if (repository != null) repositories.deleteById(repository);
    }
}
