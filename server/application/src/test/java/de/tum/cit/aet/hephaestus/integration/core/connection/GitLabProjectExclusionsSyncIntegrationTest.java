package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.sync.push.SyncEventHint;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.Disposable;
import tools.jackson.databind.ObjectMapper;

class GitLabProjectExclusionsSyncIntegrationTest extends AbstractWorkspaceIntegrationTest {
    private static final Duration STREAM_WAIT = Duration.ofSeconds(10);
    // The real hub coalesces for one second before writing. Silence must span that window.
    private static final Duration QUIET_WINDOW = Duration.ofSeconds(2);
    private static final long PROJECT_ID = 4101L;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithAdminUser
    void shouldPushReceiptChangesOnlyAfterTheirTransactionCommits() {
        String slug = "project-sync-" + UUID.randomUUID();
        Workspace workspace = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaces,
                connections,
                WorkspaceTestFixtures.gitLabPatWorkspace(slug).withSlug(slug),
                "https://gitlab.example.test");
        ensureAdminMembership(workspace);
        Connection connection = connections
                .findActive(workspace.getId(), IntegrationKind.GITLAB)
                .orElseThrow();
        UUID firstReceipt = UUID.randomUUID();
        var config = (ConnectionConfig.GitLabConfig) connection.getConfig();
        connection.setConfig(
                config.withProjectExclusions(config.projectExclusions().stop(PROJECT_ID, firstReceipt)));
        connections.saveAndFlush(connection);

        List<ServerSentEvent<String>> events = new CopyOnWriteArrayList<>();
        Disposable subscription = webTestClient
                .get()
                .uri("/workspaces/{slug}/sync/events", slug)
                .headers(TestAuthUtils.withCurrentUser())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .getResponseBody()
                .subscribe(events::add);
        try {
            await().atMost(STREAM_WAIT)
                    .untilAsserted(() -> assertThat(events).anyMatch(event -> "connected".equals(event.comment())));

            transactions.executeWithoutResult(status -> {
                assertThat(connectionService.updateGitLabProjectExclusions(
                                workspace.getId(), exclusions -> exclusions.withoutStopReceipt(PROJECT_ID)))
                        .isPresent();
                assertNoResourcesHint(events, connection.getId());
                status.setRollbackOnly();
            });
            assertThat(exclusions(workspace).stopReceipt(PROJECT_ID)).contains(firstReceipt);
            assertNoResourcesHint(events, connection.getId());

            // Only the receipt changes: the same project and path were stopped again.
            UUID secondReceipt = UUID.randomUUID();
            transactions.executeWithoutResult(status -> assertThat(connectionService.updateGitLabProjectExclusions(
                            workspace.getId(), exclusions -> exclusions.stop(PROJECT_ID, secondReceipt)))
                    .isPresent());

            await().atMost(STREAM_WAIT)
                    .untilAsserted(() -> assertThat(events)
                            .filteredOn(event -> isResourcesHint(event, connection.getId()))
                            .hasSize(1));
            assertThat(exclusions(workspace).stopReceipt(PROJECT_ID)).contains(secondReceipt);
        } finally {
            subscription.dispose();
        }
    }

    private ConnectionConfig.GitLabProjectExclusions exclusions(Workspace workspace) {
        var config = (ConnectionConfig.GitLabConfig) connections
                .findActive(workspace.getId(), IntegrationKind.GITLAB)
                .orElseThrow()
                .getConfig();
        return config.projectExclusions();
    }

    private void assertNoResourcesHint(List<ServerSentEvent<String>> events, long connectionId) {
        await().during(QUIET_WINDOW)
                .atMost(STREAM_WAIT)
                .untilAsserted(() -> assertThat(events).noneMatch(event -> isResourcesHint(event, connectionId)));
    }

    private boolean isResourcesHint(ServerSentEvent<String> event, long connectionId) {
        String data = event.data();
        if (!"sync".equals(event.event()) || data == null) return false;
        SyncEventHint hint = objectMapper.readValue(data, SyncEventHint.class);
        return "resources".equals(hint.scope()) && hint.connectionId() == connectionId;
    }
}
