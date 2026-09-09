package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

class AgentJobControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AgentJobRepository agentJobRepository;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private Workspace setupWorkspace() {
        User owner = persistUser("job-owner");
        Workspace workspace = createWorkspace("job-ws", "Job Workspace", "job-org", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        return workspace;
    }

    private static String seededJobToken() {
        return "job-token-must-not-leak-" + UUID.randomUUID();
    }

    private AgentJob createJob(Workspace workspace, AgentJobStatus status) {
        AgentJob job = new AgentJob();
        job.setJobToken(seededJobToken());
        job.setWorkspace(workspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setStatus(status);
        job.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of(
                "agent_type", "CLAUDE_CODE", "model", "claude-sonnet-4-20250514", "upstreamModelId", "gpt-5.4-mini")));
        return agentJobRepository.save(job);
    }

    @Test
    @WithAdminUser
    void shouldScopeExecutionArchivesAndAvoidCachingPrivateCapture() {
        Workspace workspace = setupWorkspace();
        AgentJob job = createJob(workspace, AgentJobStatus.COMPLETED);
        webTestClient
                .get()
                .uri("/workspaces/{slug}/agents/jobs/{id}/execution-archive", workspace.getWorkspaceSlug(), job.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueMatches("Cache-Control", ".*no-store.*")
                .expectBody()
                .jsonPath("$.jobId")
                .isEqualTo(job.getId().toString())
                .jsonPath("$.attempts.length()")
                .isEqualTo(0);
        User owner = persistUser("capture-other-owner");
        Workspace other = createWorkspace("capture-other", "Other", "capture-other-org", AccountType.ORG, owner);
        ensureAdminMembership(other);
        webTestClient
                .get()
                .uri("/workspaces/{slug}/agents/jobs/{id}/execution-archive", other.getWorkspaceSlug(), job.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound();
        webTestClient
                .get()
                .uri(
                        "/workspaces/{slug}/agents/jobs/{id}/execution-archive/0/files/{sha}",
                        other.getWorkspaceSlug(),
                        job.getId(),
                        "a".repeat(64))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @WithAdminUser
    void listJobsReturnsEmptyPageWhenNoJobs() {
        Workspace workspace = setupWorkspace();

        webTestClient
                .get()
                .uri("/workspaces/{slug}/agents/jobs", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(0)
                .jsonPath("$.content")
                .isArray()
                .jsonPath("$.content.length()")
                .isEqualTo(0);
    }

    @Test
    @WithAdminUser
    void listJobsReturnsPaginatedResults() {
        Workspace workspace = setupWorkspace();

        createJob(workspace, AgentJobStatus.COMPLETED);
        createJob(workspace, AgentJobStatus.RUNNING);
        createJob(workspace, AgentJobStatus.QUEUED);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/agents/jobs?size=2", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(3)
                .jsonPath("$.content.length()")
                .isEqualTo(2)
                .jsonPath("$.totalPages")
                .isEqualTo(2);
    }

    @Test
    @WithAdminUser
    void listJobsFiltersByStatus() {
        Workspace workspace = setupWorkspace();

        createJob(workspace, AgentJobStatus.COMPLETED);
        createJob(workspace, AgentJobStatus.RUNNING);
        createJob(workspace, AgentJobStatus.QUEUED);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/agents/jobs?status=RUNNING", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].status")
                .isEqualTo("RUNNING");
    }

    @Test
    @WithAdminUser
    void getJobReturnsJobDetail() {
        Workspace workspace = setupWorkspace();
        AgentJob job = createJob(workspace, AgentJobStatus.COMPLETED);

        AgentJobDTO result = webTestClient
                .get()
                .uri("/workspaces/{slug}/agents/jobs/{id}", workspace.getWorkspaceSlug(), job.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(AgentJobDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(job.getId());
        assertThat(result.status()).isEqualTo(AgentJobStatus.COMPLETED);
        assertThat(result.jobType()).isEqualTo(AgentJobType.PULL_REQUEST_REVIEW);
        assertThat(result.model()).isEqualTo("gpt-5.4-mini");
    }

    @Test
    @WithAdminUser
    void getJobReturns404ForNonExistentId() {
        Workspace workspace = setupWorkspace();

        webTestClient
                .get()
                .uri("/workspaces/{slug}/agents/jobs/{id}", workspace.getWorkspaceSlug(), UUID.randomUUID())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @WithAdminUser
    void getJobReturns404ForJobInDifferentWorkspace() {
        Workspace workspaceA = setupWorkspace();

        User ownerB = persistUser("job-owner-b");
        Workspace workspaceB = createWorkspace("job-ws-b", "Job B", "job-org-b", AccountType.ORG, ownerB);
        ensureAdminMembership(workspaceB);

        AgentJob jobInA = createJob(workspaceA, AgentJobStatus.COMPLETED);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/agents/jobs/{id}", workspaceB.getWorkspaceSlug(), jobInA.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @WithAdminUser
    void jobTokenNeverExposedInResponse() {
        Workspace workspace = setupWorkspace();
        AgentJob job = createJob(workspace, AgentJobStatus.QUEUED);

        String responseBody = webTestClient
                .get()
                .uri("/workspaces/{slug}/agents/jobs/{id}", workspace.getWorkspaceSlug(), job.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // The token the row actually holds, read back through the at-rest converter: the DTO carries no
        // jobToken component, so what has to be pinned is the VALUE, which could still ride out inside
        // the passthrough JSONB fields (metadata / output / configSnapshot).
        AgentJob freshJob = agentJobRepository.findById(job.getId()).orElseThrow();
        assertThat(freshJob.getJobToken())
                .as("the fixture must carry a token, or this asserts nothing")
                .isNotBlank();
        assertThat(responseBody).isNotNull().doesNotContain(freshJob.getJobToken());
    }

    @Test
    @WithAdminUser
    void cancelQueuedJobReturns200() {
        Workspace workspace = setupWorkspace();
        AgentJob job = createJob(workspace, AgentJobStatus.QUEUED);

        AgentJobDTO result = webTestClient
                .post()
                .uri("/workspaces/{slug}/agents/jobs/{id}/cancel", workspace.getWorkspaceSlug(), job.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(AgentJobDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(AgentJobStatus.CANCELLED);
    }

    @Test
    @WithAdminUser
    void cancelRunningJobReturns200() {
        Workspace workspace = setupWorkspace();
        AgentJob job = createJob(workspace, AgentJobStatus.RUNNING);

        webTestClient
                .post()
                .uri("/workspaces/{slug}/agents/jobs/{id}/cancel", workspace.getWorkspaceSlug(), job.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("CANCELLED");
    }

    @Test
    @WithAdminUser
    void cancelCompletedJobReturns409() {
        Workspace workspace = setupWorkspace();
        AgentJob job = createJob(workspace, AgentJobStatus.COMPLETED);

        webTestClient
                .post()
                .uri("/workspaces/{slug}/agents/jobs/{id}/cancel", workspace.getWorkspaceSlug(), job.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isEqualTo(409);
    }

    @Test
    @WithAdminUser
    void cancelNonExistentJobReturns404() {
        Workspace workspace = setupWorkspace();

        webTestClient
                .post()
                .uri("/workspaces/{slug}/agents/jobs/{id}/cancel", workspace.getWorkspaceSlug(), UUID.randomUUID())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @WithAdminUser
    void cancelJobInDifferentWorkspaceReturns404() {
        Workspace workspaceA = setupWorkspace();

        User ownerB = persistUser("cancel-owner-b");
        Workspace workspaceB = createWorkspace("cancel-ws-b", "Cancel B", "cancel-org-b", AccountType.ORG, ownerB);
        ensureAdminMembership(workspaceB);

        AgentJob jobInA = createJob(workspaceA, AgentJobStatus.QUEUED);

        webTestClient
                .post()
                .uri("/workspaces/{slug}/agents/jobs/{id}/cancel", workspaceB.getWorkspaceSlug(), jobInA.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @WithAdminUser
    void cancelAlreadyCancelledJobIsIdempotent() {
        Workspace workspace = setupWorkspace();
        AgentJob job = createJob(workspace, AgentJobStatus.CANCELLED);

        AgentJobDTO result = webTestClient
                .post()
                .uri("/workspaces/{slug}/agents/jobs/{id}/cancel", workspace.getWorkspaceSlug(), job.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(AgentJobDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(AgentJobStatus.CANCELLED);
    }

    /**
     * {@code SecurityConfig} permits anonymous GET under a workspace slug, so
     * {@code @RequireAtLeastWorkspaceAdmin} on the handler is the only thing between the public internet
     * and a workspace's job history.
     */
    @Test
    void listJobsRequiresAuthentication() {
        User owner = persistUser("unauth-job-owner");
        Workspace workspace = createWorkspace("unauth-job-ws", "Unauth", "unauth-job", AccountType.ORG, owner);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/agents/jobs", workspace.getWorkspaceSlug())
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }
}
