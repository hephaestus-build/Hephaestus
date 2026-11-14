package de.tum.cit.aet.hephaestus.integration.core.sync.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobStatus;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;

@DisplayName("Sync progress for workspace members")
class SyncProgressControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private SyncJobRepository syncJobRepository;

    private Workspace workspace;
    private Workspace otherWorkspace;

    @BeforeEach
    void setUp() {
        workspace = workspaceOwnedBy("progress-owner", "progress-ws");
        otherWorkspace = workspaceOwnedBy("progress-other-owner", "progress-other-ws");
    }

    @Test
    @WithMentorUser
    @DisplayName("a member reads that no sync runs, that one runs, and that it ended")
    void memberReadsTheFact() {
        ensureMembership(workspace, persistUser("mentor"), WorkspaceRole.MEMBER);
        assertThat(syncing(workspace)).isFalse();

        SyncJob job = activeJob(workspace);
        assertThat(syncing(workspace)).isTrue();

        ReflectionTestUtils.setField(job, "status", SyncJobStatus.SUCCEEDED);
        syncJobRepository.save(job);
        assertThat(syncing(workspace)).isFalse();
    }

    @Test
    @WithMentorUser
    @DisplayName("another workspace's sync does not count")
    void anotherWorkspaceDoesNotCount() {
        ensureMembership(workspace, persistUser("mentor"), WorkspaceRole.MEMBER);
        activeJob(otherWorkspace);

        assertThat(syncing(workspace)).isFalse();
    }

    @Test
    @WithMentorUser
    @DisplayName("someone outside the workspace reads nothing")
    void shouldAnswerNotFoundWhenCallerIsOutsideTheWorkspace() {
        persistUser("mentor");
        activeJob(otherWorkspace);

        request(otherWorkspace).expectStatus().isNotFound();
    }

    private Workspace workspaceOwnedBy(String owner, String slug) {
        User user = persistUser(owner + "-" + System.nanoTime());
        return createWorkspace(slug + "-" + System.nanoTime(), slug, slug, AccountType.ORG, user);
    }

    private SyncJob activeJob(Workspace target) {
        long nativeId = System.nanoTime();
        Connection connection = connectionRepository.save(new Connection(
                target,
                IntegrationKind.GITHUB,
                String.valueOf(nativeId),
                new ConnectionConfig.GitHubAppConfig(nativeId, "progress-org", null, Set.of())));
        ConnectionFixtures.reachState(connection, IntegrationState.ACTIVE);
        connection = connectionRepository.save(connection);
        return syncJobRepository.save(new SyncJob(
                target, connection, IntegrationKind.GITHUB, SyncJobType.RECONCILIATION, SyncJobTrigger.MANUAL, null));
    }

    private boolean syncing(Workspace target) {
        WorkspaceSyncProgressDTO progress = request(target)
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceSyncProgressDTO.class)
                .returnResult()
                .getResponseBody();
        assertThat(progress).isNotNull();
        return progress.syncing();
    }

    private WebTestClient.ResponseSpec request(Workspace target) {
        return webTestClient
                .get()
                .uri("/workspaces/{slug}/sync/progress", target.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange();
    }
}
