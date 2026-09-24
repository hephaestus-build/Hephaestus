package de.tum.cit.aet.hephaestus.agent.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The confirmation step, end to end: a preflight must cost nothing, and the state a run is in must be
 * the only thing that decides what can be done to it.
 */
class ReviewBackfillControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ReviewBackfillRunRepository runRepository;

    /**
     * Authenticated the way production is: a numeric JWT {@code sub}, which is what
     * {@code SecurityUtils.getCurrentAccountId()} reads (ADR 0017). {@code @WithAdminUser}'s token
     * carries a non-numeric subject, and a campaign refuses to be created without an account to
     * attribute the spend to — so a test using it would prove the refusal rather than the feature.
     */
    private static final String ADMIN_ACCOUNT_TOKEN = "mock-jwt-sub-1";

    private static final Instant TO = Instant.parse("2026-08-07T00:00:00Z");
    private static final Instant FROM = TO.minus(Duration.ofDays(30));

    private Workspace setupWorkspace(String slug) {
        User owner = persistUser(slug + "-owner");
        Workspace workspace = createWorkspace(slug, "Backfill Workspace", slug + "-org", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        return workspace;
    }

    private static java.util.function.Consumer<org.springframework.http.HttpHeaders> asAdminAccount() {
        return headers -> headers.setBearerAuth(ADMIN_ACCOUNT_TOKEN);
    }

    private Map<String, Object> window() {
        return Map.of("artifactKind", "scm.pull_request", "fromAt", FROM.toString(), "toAt", TO.toString());
    }

    /** Asking what a backfill would cost creates a decision to make, not a campaign in progress. */
    @Test
    @WithAdminUser
    void aPreflightReturnsARunAwaitingConfirmationAndAuthorisesNothing() {
        Workspace workspace = setupWorkspace("backfill-preflight");

        webTestClient
                .post()
                .uri("/workspaces/{slug}/practices/backfill-runs", workspace.getWorkspaceSlug())
                .headers(asAdminAccount())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(window())
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("AWAITING_CONFIRMATION")
                .jsonPath("$.estimatedArtifacts")
                .isEqualTo(0)
                .jsonPath("$.submittedCount")
                .isEqualTo(0)
                .jsonPath("$.confirmedByAccountId")
                .doesNotExist()
                .jsonPath("$.startedAt")
                .doesNotExist();

        assertThat(runRepository.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getStatus()).isEqualTo(ReviewBackfillStatus.AWAITING_CONFIRMATION);
            assertThat(run.getConfirmedByAccountId()).isNull();
            assertThat(run.getCursorArtifactId()).isNull();
        });
    }

    @Test
    @WithAdminUser
    void confirmingRecordsWhoAuthorisedTheSpend() {
        Workspace workspace = setupWorkspace("backfill-confirm");
        String runId = preflight(workspace);

        webTestClient
                .patch()
                .uri("/workspaces/{slug}/practices/backfill-runs/{runId}/status", workspace.getWorkspaceSlug(), runId)
                .headers(asAdminAccount())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("status", "RUNNING"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("RUNNING")
                .jsonPath("$.confirmedByAccountId")
                .exists()
                .jsonPath("$.startedAt")
                .exists();
    }

    @Test
    @WithAdminUser
    void aCancelledCampaignCannotBeRestarted() {
        Workspace workspace = setupWorkspace("backfill-restart");
        String runId = preflight(workspace);
        patchStatus(workspace, runId, "CANCELLED").expectStatus().isOk().expectBody(Void.class);

        patchStatus(workspace, runId, "RUNNING").expectStatus().isEqualTo(409).expectBody(Void.class);
    }

    /**
     * Two overlapping campaigns would each read the other's ledger rows as "already covered", so neither
     * would cover the scope its estimate described.
     */
    @Test
    @WithAdminUser
    void aSecondCampaignIsRefusedWhileOneIsUnderWay() {
        Workspace workspace = setupWorkspace("backfill-second");
        String runId = preflight(workspace);
        patchStatus(workspace, runId, "RUNNING").expectStatus().isOk().expectBody(Void.class);

        webTestClient
                .post()
                .uri("/workspaces/{slug}/practices/backfill-runs", workspace.getWorkspaceSlug())
                .headers(asAdminAccount())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(window())
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(Void.class);
    }

    /** Told at preflight, so the admin narrows the window instead of discovering the limit later. */
    @Test
    @WithAdminUser
    void anInvertedWindowIsRefusedWithAProblemDetail() {
        Workspace workspace = setupWorkspace("backfill-window");

        webTestClient
                .post()
                .uri("/workspaces/{slug}/practices/backfill-runs", workspace.getWorkspaceSlug())
                .headers(asAdminAccount())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("artifactKind", "scm.pull_request", "fromAt", TO.toString(), "toAt", FROM.toString()))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void aConversationThreadCannotBeBackfilled() {
        Workspace workspace = setupWorkspace("backfill-kind");

        webTestClient
                .post()
                .uri("/workspaces/{slug}/practices/backfill-runs", workspace.getWorkspaceSlug())
                .headers(asAdminAccount())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "artifactKind", "chat.conversation_thread", "fromAt", FROM.toString(), "toAt", TO.toString()))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    /** A campaign can spend a workspace's whole monthly AI budget, so a plain member cannot start one. */
    @Test
    @WithMentorUser
    void aWorkspaceMemberCanNeitherSeeNorStartACampaign() {
        Workspace workspace = setupWorkspace("backfill-member");
        User member = persistUser("mentor");
        ensureWorkspaceMembership(workspace, member, WorkspaceRole.MEMBER);
        String slug = workspace.getWorkspaceSlug();

        webTestClient
                .get()
                .uri("/workspaces/{slug}/practices/backfill-runs", slug)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        webTestClient
                .post()
                .uri("/workspaces/{slug}/practices/backfill-runs", slug)
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(window())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void aCampaignFromAnotherWorkspaceIsNotFound() {
        Workspace first = setupWorkspace("backfill-tenant-a");
        Workspace second = setupWorkspace("backfill-tenant-b");
        String runId = preflight(first);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/practices/backfill-runs/{runId}", second.getWorkspaceSlug(), runId)
                .headers(asAdminAccount())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    private String preflight(Workspace workspace) {
        byte[] body = webTestClient
                .post()
                .uri("/workspaces/{slug}/practices/backfill-runs", workspace.getWorkspaceSlug())
                .headers(asAdminAccount())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(window())
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .returnResult()
                .getResponseBody();
        assertThat(body).isNotNull();
        return runRepository
                .findByWorkspaceIdOrderByCreatedAtDesc(
                        workspace.getId(), org.springframework.data.domain.PageRequest.ofSize(1))
                .getFirst()
                .getId()
                .toString();
    }

    private WebTestClient.ResponseSpec patchStatus(Workspace workspace, String runId, String status) {
        return webTestClient
                .patch()
                .uri("/workspaces/{slug}/practices/backfill-runs/{runId}/status", workspace.getWorkspaceSlug(), runId)
                .headers(asAdminAccount())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("status", status))
                .exchange();
    }
}
