package de.tum.cit.aet.hephaestus.integration.core.sync.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncDetails;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncStateProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationSyncRunner;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobHandle;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobStatus;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Controller-level coverage for the unified sync surface: status for a connected + a non-ACTIVE
 * connection (must NOT 404), the manual-trigger idempotent-absorb ("Sync now" then a duplicate),
 * cancel, the catalog endpoint, and the class-level {@code @RequireAtLeastWorkspaceAdmin} gate.
 *
 * <p>The test-profile {@code applicationTaskExecutor} runs {@code @Async}/executor work
 * SYNCHRONOUSLY on the calling thread (see {@code TestAsyncConfiguration}), so a POST that
 * dispatches work through it only returns once the dispatched body finishes. Scenarios that need to
 * observe a job while still RUNNING therefore fire the request on a background thread and use the
 * mock runner's cooperative-cancellation answer (or a start/release latch pair) to control timing.
 */
@Import(SyncControllerTestConfiguration.class)
class SyncControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private SyncControllerTestConfiguration.SyncControllerTestDriver syncDriver;

    private ConnectionSyncStateProvider githubSyncStateProvider;
    private IntegrationSyncRunner githubSyncRunner;

    @Autowired
    private SyncJobRepository syncJobRepository;

    private Workspace workspace;
    private Connection connection;

    /** A second tenant the test's admin has NO membership in — the cross-tenant IDOR target. */
    private Workspace otherWorkspace;

    private Connection otherConnection;
    private SyncJob otherJob;

    @BeforeEach
    void setUp() {
        githubSyncStateProvider = syncDriver.stateProvider();
        githubSyncRunner = syncDriver.runner();
        Mockito.reset(githubSyncStateProvider, githubSyncRunner);
        Mockito.when(githubSyncStateProvider.kind()).thenReturn(IntegrationKind.GITHUB);
        Mockito.when(githubSyncStateProvider.describe(any(), anyLong())).thenReturn(ConnectionSyncDetails.empty());
        Mockito.when(githubSyncStateProvider.resources(any(), anyLong())).thenReturn(List.of());
        Mockito.when(githubSyncRunner.kind()).thenReturn(IntegrationKind.GITHUB);
        Mockito.doNothing().when(githubSyncRunner).reconcile(any(), any(), any());

        User owner = persistUser("sync-owner-" + System.nanoTime());
        workspace = createWorkspace(
                "sync-ws-" + System.nanoTime(), "Sync Observability Test", "sync-org", AccountType.ORG, owner);
        connection = connectionRepository.save(new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "100",
                new ConnectionConfig.GitHubAppConfig(100L, "sync-org", null, Set.of())));
        connection.setState(IntegrationState.ACTIVE);
        connection = connectionRepository.save(connection);

        // A second tenant, owned by a different user. The @WithAdminUser principal is made an ADMIN of
        // `workspace` only (via ensureAdminMembership) and never of this one, so every request it makes
        // against these ids is a genuine cross-tenant reach.
        User otherOwner = persistUser("sync-other-owner-" + System.nanoTime());
        otherWorkspace = createWorkspace(
                "sync-other-ws-" + System.nanoTime(), "Other Tenant", "other-org", AccountType.ORG, otherOwner);
        otherConnection = connectionRepository.save(new Connection(
                otherWorkspace,
                IntegrationKind.GITHUB,
                "200",
                new ConnectionConfig.GitHubAppConfig(200L, "other-org", null, Set.of())));
        otherConnection.setState(IntegrationState.ACTIVE);
        otherConnection = connectionRepository.save(otherConnection);
        otherJob = syncJobRepository.save(new SyncJob(
                otherWorkspace,
                otherConnection,
                IntegrationKind.GITHUB,
                SyncJobType.RECONCILIATION,
                SyncJobTrigger.MANUAL,
                null));
    }

    @Test
    @WithAdminUser
    void status_connectedActiveConnection_returns200Healthy() {
        ensureAdminMembership(workspace);

        ConnectionSyncStatusDTO status = statusRequest()
                .expectStatus()
                .isOk()
                .expectBody(ConnectionSyncStatusDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(status).isNotNull();
        assertThat(status.connectionId()).isEqualTo(connection.getId());
        assertThat(status.connectionState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(status.health()).isEqualTo(ConnectionHealth.HEALTHY);
        assertThat(status.activeJob()).isNull();
    }

    @Test
    @WithAdminUser
    @DisplayName("a SUSPENDED connection still answers 200 with a state-aware DTO, never 404")
    void status_suspendedConnection_returns200NotSuspendedHealth() {
        ensureAdminMembership(workspace);
        connection.setState(IntegrationState.SUSPENDED);
        connectionRepository.save(connection);

        ConnectionSyncStatusDTO status = statusRequest()
                .expectStatus()
                .isOk()
                .expectBody(ConnectionSyncStatusDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(status).isNotNull();
        assertThat(status.health()).isEqualTo(ConnectionHealth.SUSPENDED);
    }

    @Test
    @WithAdminUser
    void status_unknownConnectionId_returns404() {
        ensureAdminMembership(workspace);

        webTestClient
                .get()
                .uri(
                        "/workspaces/{slug}/connections/{id}/sync",
                        workspace.getWorkspaceSlug(),
                        connection.getId() + 999_999)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void trigger_thenDuplicateWhileRunning_isIdempotentAbsorb() throws InterruptedException {
        ensureAdminMembership(workspace);
        // Captured on the TEST thread: @WithAdminUser's SecurityContext lives in a plain (non-inheritable)
        // ThreadLocal, so a background Thread sees no authentication unless the token travels explicitly.
        String adminToken = TestAuthUtils.getCurrentUserToken();
        CountDownLatch runnerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    runnerStarted.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                })
                .when(githubSyncRunner)
                .reconcile(any(), any(), any());

        AtomicReference<WebTestClient.ResponseSpec> firstResponse = new AtomicReference<>();
        Thread firstCaller = new Thread(() -> firstResponse.set(triggerRequest(adminToken)));
        firstCaller.start();

        assertThat(runnerStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // Duplicate manual trigger while the first job is still RUNNING: idempotent-absorb, 200 not 409.
        SyncJobDTO duplicateJob = triggerRequest(adminToken)
                .expectStatus()
                .isOk()
                .expectBody(SyncJobDTO.class)
                .returnResult()
                .getResponseBody();
        assertThat(duplicateJob).isNotNull();

        release.countDown();
        firstCaller.join(5000);

        WebTestClient.ResponseSpec response = firstResponse.get();
        assertNotNull(response);
        SyncJobDTO firstJob = response.expectStatus()
                .isEqualTo(HttpStatus.ACCEPTED)
                .expectBody(SyncJobDTO.class)
                .returnResult()
                .getResponseBody();
        assertThat(firstJob).isNotNull();
        assertThat(duplicateJob.id()).isEqualTo(firstJob.id());
    }

    @Test
    @WithAdminUser
    @DisplayName("a BACKFILL requested while a RECONCILIATION is running is a type conflict → 409, not a 200-absorb")
    void trigger_backfillWhileReconciliationRunning_returns409NotAbsorb() throws InterruptedException {
        ensureAdminMembership(workspace);
        Mockito.when(githubSyncRunner.supportsBackfill()).thenReturn(true);
        String adminToken = TestAuthUtils.getCurrentUserToken();
        CountDownLatch runnerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    runnerStarted.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                })
                .when(githubSyncRunner)
                .reconcile(any(), any(), any());

        AtomicReference<WebTestClient.ResponseSpec> firstResponse = new AtomicReference<>();
        Thread firstCaller = new Thread(() -> firstResponse.set(triggerRequest(adminToken))); // RECONCILIATION
        firstCaller.start();
        assertThat(runnerStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // Different-type request while the reconciliation holds the one-active slot: genuine conflict, not absorb.
        triggerRequest(adminToken, SyncJobType.BACKFILL)
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody(Void.class);

        release.countDown();
        firstCaller.join(5000);
    }

    @Test
    @WithAdminUser
    @DisplayName("an empty body (missing sync type) is a 400, not a misleading 409 from the NOT-NULL column")
    void trigger_missingType_returns400() {
        ensureAdminMembership(workspace);
        webTestClient
                .post()
                .uri("/workspaces/{slug}/connections/{id}/sync/jobs", workspace.getWorkspaceSlug(), connection.getId())
                .headers(h -> h.setBearerAuth(TestAuthUtils.getCurrentUserToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void trigger_notActiveConnection_returns409() {
        ensureAdminMembership(workspace);
        connection.setState(IntegrationState.SUSPENDED);
        connectionRepository.save(connection);

        triggerRequest(TestAuthUtils.getCurrentUserToken())
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void cancel_runningJob_stopsItCooperatively() throws InterruptedException {
        ensureAdminMembership(workspace);
        String adminToken = TestAuthUtils.getCurrentUserToken();
        CountDownLatch runnerStarted = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    runnerStarted.countDown();
                    SyncJobHandle handle = invocation.getArgument(1);
                    long deadline = System.currentTimeMillis() + 5000;
                    while (!handle.isCancellationRequested() && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(25);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                    }
                    // A real cooperative runner reports that it stopped early so the engine finalizes CANCELLED;
                    // a body that merely returns after seeing the flag is (correctly) recorded SUCCEEDED.
                    if (handle.isCancellationRequested()) {
                        handle.reportCancelled();
                    }
                    return null;
                })
                .when(githubSyncRunner)
                .reconcile(any(), any(), any());

        AtomicReference<WebTestClient.ResponseSpec> firstResponse = new AtomicReference<>();
        Thread firstCaller = new Thread(() -> firstResponse.set(triggerRequest(adminToken)));
        firstCaller.start();
        assertThat(runnerStarted.await(5, TimeUnit.SECONDS)).isTrue();

        SyncJobDTO created = duplicateJobDuringRun(adminToken);

        webTestClient
                .patch()
                .uri(
                        "/workspaces/{slug}/connections/{id}/sync/jobs/{jobId}",
                        workspace.getWorkspaceSlug(),
                        connection.getId(),
                        created.id())
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"cancelRequested\":true}")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.ACCEPTED)
                .expectBody(Void.class);

        firstCaller.join(5000);

        ConnectionSyncStatusDTO status = statusRequest()
                .expectStatus()
                .isOk()
                .expectBody(ConnectionSyncStatusDTO.class)
                .returnResult()
                .getResponseBody();
        assertThat(status).isNotNull();
        assertThat(status.activeJob()).isNull();
        assertThat(status.lastJob()).isNotNull();
        assertThat(status.lastJob().status()).isEqualTo(SyncJobStatus.CANCELLED);
    }

    @Test
    @WithAdminUser
    void updateJob_withoutCancellationRequest_returns400() {
        ensureAdminMembership(workspace);

        webTestClient
                .patch()
                .uri(
                        "/workspaces/{slug}/connections/{id}/sync/jobs/{jobId}",
                        workspace.getWorkspaceSlug(),
                        connection.getId(),
                        1L)
                .headers(h -> h.setBearerAuth(TestAuthUtils.getCurrentUserToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"cancelRequested\":false}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void catalog_listsGithubAsConnected() {
        ensureAdminMembership(workspace);

        List<IntegrationCatalogEntryDTO> catalog = webTestClient
                .get()
                .uri("/workspaces/{slug}/connections/catalog", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(IntegrationCatalogEntryDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(catalog).isNotNull();
        IntegrationCatalogEntryDTO github = catalog.stream()
                .filter(e -> e.kind() == IntegrationKind.GITHUB)
                .findFirst()
                .orElseThrow(() -> new AssertionError("GITHUB missing from catalog"));
        assertThat(github.connected()).isTrue();
        assertThat(github.connectionId()).isEqualTo(connection.getId());
    }

    @Test
    @WithMentorUser
    @DisplayName("a workspace MEMBER is forbidden from every sync endpoint")
    void nonAdminMember_forbiddenEverywhere() {
        User member = persistUser("mentor");
        ensureWorkspaceMembership(workspace, member, WorkspaceRole.MEMBER);

        statusRequest().expectStatus().isForbidden().expectBody(Void.class);
        webTestClient
                .get()
                .uri("/workspaces/{slug}/connections/catalog", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        triggerRequest(TestAuthUtils.getCurrentUserToken())
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    @DisplayName("an admin of workspace A cannot reach workspace B's connection through ANY sync endpoint")
    void crossTenantConnection_everyEndpointReturns404() {
        ensureAdminMembership(workspace);
        String adminToken = TestAuthUtils.getCurrentUserToken();
        long foreignId = otherConnection.getId();

        // 404, never 403: distinguishing "no such connection" from "exists, but not yours" would hand a
        // probing admin an existence oracle over other tenants' connection ids. And never 200 — that is
        // the IDOR itself. Both wrong answers are asserted against by pinning the exact status.
        webTestClient
                .get()
                .uri("/workspaces/{slug}/connections/{id}/sync", workspace.getWorkspaceSlug(), foreignId)
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/connections/{id}/sync/resources", workspace.getWorkspaceSlug(), foreignId)
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/connections/{id}/sync/jobs", workspace.getWorkspaceSlug(), foreignId)
                .headers(h -> h.setBearerAuth(adminToken))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);

        webTestClient
                .post()
                .uri("/workspaces/{slug}/connections/{id}/sync/jobs", workspace.getWorkspaceSlug(), foreignId)
                .headers(h -> h.setBearerAuth(adminToken))
                .bodyValue(new TriggerSyncJobRequestDTO(SyncJobType.RECONCILIATION))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);

        webTestClient
                .patch()
                .uri(
                        "/workspaces/{slug}/connections/{id}/sync/jobs/{jobId}",
                        workspace.getWorkspaceSlug(),
                        foreignId,
                        otherJob.getId())
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"cancelRequested\":true}")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);

        // The reach must not have mutated the other tenant's job on the way to the 404.
        assertThat(syncJobRepository.findById(otherJob.getId()).orElseThrow().isCancelRequested())
                .isFalse();
    }

    @Test
    @WithAdminUser
    @DisplayName("another tenant's job id, laundered through the admin's OWN connection, is still a 404")
    void crossTenantJobViaOwnConnection_returns404AndDoesNotCancel() {
        // The cancel path resolves the job by (jobId, workspaceId) rather than via the connection, so it
        // needs its own cross-tenant probe: a valid connection the caller genuinely owns, paired with a
        // job id belonging to another tenant.
        ensureAdminMembership(workspace);

        webTestClient
                .patch()
                .uri(
                        "/workspaces/{slug}/connections/{id}/sync/jobs/{jobId}",
                        workspace.getWorkspaceSlug(),
                        connection.getId(),
                        otherJob.getId())
                .headers(h -> h.setBearerAuth(TestAuthUtils.getCurrentUserToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"cancelRequested\":true}")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);

        assertThat(syncJobRepository.findById(otherJob.getId()).orElseThrow().isCancelRequested())
                .isFalse();
    }

    // --- helpers ---

    private WebTestClient.ResponseSpec statusRequest() {
        return webTestClient
                .get()
                .uri("/workspaces/{slug}/connections/{id}/sync", workspace.getWorkspaceSlug(), connection.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange();
    }

    /**
     * Takes an explicit bearer token (rather than reading {@code TestAuthUtils.withCurrentUser()} at
     * call time) so it can be invoked from a background {@link Thread}: {@code SecurityContextHolder}'s
     * default strategy is a plain (non-inheritable) {@code ThreadLocal}, so a child thread sees no
     * authentication unless the token is captured on the test thread and passed in explicitly.
     */
    private WebTestClient.ResponseSpec triggerRequest(String bearerToken) {
        return triggerRequest(bearerToken, SyncJobType.RECONCILIATION);
    }

    private WebTestClient.ResponseSpec triggerRequest(String bearerToken, SyncJobType type) {
        return webTestClient
                .post()
                .uri("/workspaces/{slug}/connections/{id}/sync/jobs", workspace.getWorkspaceSlug(), connection.getId())
                .headers(h -> h.setBearerAuth(bearerToken))
                .bodyValue(new TriggerSyncJobRequestDTO(type))
                .exchange();
    }

    /** A trigger call made WHILE another job is running answers 200 with the still-active job. */
    private SyncJobDTO duplicateJobDuringRun(String bearerToken) {
        return required(triggerRequest(bearerToken)
                .expectStatus()
                .isOk()
                .expectBody(SyncJobDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private static <T> T required(@Nullable T value) {
        assertNotNull(value);
        return value;
    }
}
