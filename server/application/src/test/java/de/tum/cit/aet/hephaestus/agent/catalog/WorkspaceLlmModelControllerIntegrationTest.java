package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class WorkspaceLlmModelControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private LlmConnectionRepository llmConnectionRepository;

    @Autowired
    private LlmModelRepository llmModelRepository;

    @Autowired
    private LlmModelWorkspaceGrantRepository llmModelWorkspaceGrantRepository;

    @Autowired
    private WorkspaceLlmModelRepository workspaceLlmModelRepository;

    private Workspace setupWorkspace(String slug) {
        User owner = persistUser(slug + "-owner");
        Workspace workspace = createWorkspace(slug, "Workspace " + slug, slug + "-org", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        return workspace;
    }

    private WorkspaceLlmConnectionDTO createWorkspaceConnection(Workspace workspace, String slug) {
        var request = new CreateWorkspaceLlmConnectionRequestDTO(
                slug,
                "My Provider",
                LlmCatalogTestFixtures.PUBLIC_BASE_URL,
                "openai-completions",
                LlmAuthMode.BEARER,
                "sk-workspace-secret",
                true);
        return Objects.requireNonNull(webTestClient
                .post()
                .uri("/workspaces/{slug}/llm/connections", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(WorkspaceLlmConnectionDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private WorkspaceLlmModelDTO createWorkspaceModel(Workspace workspace, Long connectionId, String slug) {
        var request = new CreateWorkspaceLlmModelRequestDTO(
                slug,
                "My Model",
                "gpt-5-secret-upstream-id",
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                PricingMode.NO_CHARGE,
                null,
                null,
                null,
                null,
                "Test-owned model has no per-token charge");
        return Objects.requireNonNull(webTestClient
                .post()
                .uri(
                        "/workspaces/{slug}/llm/connections/{connectionId}/models",
                        workspace.getWorkspaceSlug(),
                        connectionId)
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(WorkspaceLlmModelDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private void saveGrant(Long modelId, Long workspaceId) {
        LlmModelWorkspaceGrant grant = new LlmModelWorkspaceGrant(modelId, workspaceId);
        grant.setGrantedAt(Instant.now());
        llmModelWorkspaceGrantRepository.save(grant);
    }

    private LlmModel seedInstanceModel(String upstreamId, ModelVisibility visibility, boolean enabled) {
        LlmConnection connection =
                llmConnectionRepository.save(LlmCatalogTestFixtures.connection("instance-conn-" + System.nanoTime()));
        return llmModelRepository.save(LlmCatalogTestFixtures.model(
                connection, "instance-model-" + System.nanoTime(), upstreamId, visibility, enabled));
    }

    @Test
    @WithAdminUser
    void workspaceAdminCanCreateGetListUpdateAndDeleteAModel() {
        Workspace workspace = setupWorkspace("wsmodel-crud-ws");
        WorkspaceLlmConnectionDTO connection = createWorkspaceConnection(workspace, "conn-1");
        WorkspaceLlmModelDTO created = createWorkspaceModel(workspace, connection.id(), "model-1");

        assertThat(created).isNotNull();
        assertThat(created.slug()).isEqualTo("model-1");

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/models/{id}", workspace.getWorkspaceSlug(), created.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.slug")
                .isEqualTo("model-1");

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/models", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1);

        var updateRequest = new UpdateWorkspaceLlmModelRequestDTO(
                "Renamed Model", null, null, null, null, null, null, null, null, null, null, null, null, null);
        webTestClient
                .patch()
                .uri("/workspaces/{slug}/llm/models/{id}", workspace.getWorkspaceSlug(), created.id())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateRequest)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.displayName")
                .isEqualTo("Renamed Model");

        webTestClient
                .delete()
                .uri("/workspaces/{slug}/llm/models/{id}", workspace.getWorkspaceSlug(), created.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/models/{id}", workspace.getWorkspaceSlug(), created.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void workspaceAdminDeclaresDataHandlingAndTheTierIsDerivedOnEveryRead() {
        Workspace workspace = setupWorkspace("wsmodel-declared-ws");
        WorkspaceLlmConnectionDTO connection = createWorkspaceConnection(workspace, "conn-declared");
        var request = new CreateWorkspaceLlmModelRequestDTO(
                "declared-model",
                "Declared Model",
                "gpt-5-secret-upstream-id",
                null,
                null,
                null,
                LlmDataOperator.PROVIDER,
                LlmDataRetention.NONE,
                "EU region, DPA renews next spring",
                true,
                PricingMode.NO_CHARGE,
                null,
                null,
                null,
                null,
                "Test-owned model has no per-token charge");
        WorkspaceLlmModelDTO created = Objects.requireNonNull(webTestClient
                .post()
                .uri(
                        "/workspaces/{slug}/llm/connections/{connectionId}/models",
                        workspace.getWorkspaceSlug(),
                        connection.id())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(WorkspaceLlmModelDTO.class)
                .returnResult()
                .getResponseBody());
        assertThat(created.operatedBy()).isEqualTo(LlmDataOperator.PROVIDER);
        assertThat(created.keptAfterReply()).isEqualTo(LlmDataRetention.NONE);
        assertThat(created.dataHandlingNote()).isEqualTo("EU region, DPA renews next spring");
        assertThat(created.dataHandlingTier()).isEqualTo(DataHandlingTier.PROVIDER_NOT_KEPT);

        DataHandlingFacts stored = workspaceLlmModelRepository
                .findByIdAndWorkspaceId(created.id(), workspace.getId())
                .orElseThrow()
                .getDataHandling();
        assertThat(stored.getOperatedBy()).isEqualTo(LlmDataOperator.PROVIDER);
        assertThat(stored.getKeptAfterReply()).isEqualTo(LlmDataRetention.NONE);
        assertThat(stored.getNote()).isEqualTo("EU region, DPA renews next spring");

        webTestClient
                .patch()
                .uri("/workspaces/{slug}/llm/models/{id}", workspace.getWorkspaceSlug(), created.id())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("operatedBy", "PROVIDER"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("Declare both facts or neither");

        webTestClient
                .patch()
                .uri("/workspaces/{slug}/llm/models/{id}", workspace.getWorkspaceSlug(), created.id())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceLlmModelRequestDTO(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.operatedBy")
                .doesNotExist()
                .jsonPath("$.dataHandlingTier")
                .isEqualTo("UNDECLARED");
    }

    @Test
    @WithAdminUser
    void anAdminOfAnotherWorkspaceCannotReachThisWorkspacesModels() {
        Workspace workspaceA = setupWorkspace("wsmodel-tenancy-a");
        WorkspaceLlmConnectionDTO connectionA = createWorkspaceConnection(workspaceA, "conn-a");
        WorkspaceLlmModelDTO modelInA = createWorkspaceModel(workspaceA, connectionA.id(), "model-a");

        User ownerB = persistUser("wsmodel-tenancy-owner-b");
        Workspace workspaceB =
                createWorkspace("wsmodel-tenancy-b", "Tenancy B", "wsmodel-tenancy-org-b", AccountType.ORG, ownerB);
        ensureAdminMembership(workspaceB);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/models/{id}", workspaceB.getWorkspaceSlug(), modelInA.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void availableModelsShowsPublicInstanceModelAndOwnBYOModel() {
        Workspace workspace = setupWorkspace("avail-basic-ws");
        seedInstanceModel("gpt-5-public-upstream", ModelVisibility.PUBLIC, true);
        WorkspaceLlmConnectionDTO connection = createWorkspaceConnection(workspace, "conn-1");
        createWorkspaceModel(workspace, connection.id(), "my-model");

        List<AvailableLlmModelDTO> available = webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/available-models", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<List<AvailableLlmModelDTO>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(available).isNotNull();
        assertThat(available).hasSize(2);
        assertThat(available)
                .extracting(AvailableLlmModelDTO::scope)
                .containsExactlyInAnyOrder(LlmModelScope.SHARED, LlmModelScope.WORKSPACE);
    }

    @Test
    @WithAdminUser
    void availableModelsHidesAGrantedInstanceModelNotGrantedToThisWorkspace() {
        Workspace workspace = setupWorkspace("avail-grant-ws");
        LlmModel grantedElsewhere = seedInstanceModel("gpt-5-granted-upstream", ModelVisibility.GRANTED, true);
        User otherOwner = persistUser("avail-grant-other-owner");
        Workspace otherWorkspace =
                createWorkspace("avail-grant-other-ws", "Other", "avail-grant-other-org", AccountType.ORG, otherOwner);
        saveGrant(grantedElsewhere.getId(), otherWorkspace.getId());

        List<AvailableLlmModelDTO> available = webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/available-models", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<List<AvailableLlmModelDTO>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(available).isNotNull();
        assertThat(available).isEmpty();
    }

    @Test
    @WithAdminUser
    void availableModelsShowsAGrantedInstanceModelOnceGrantedToThisWorkspace() {
        Workspace workspace = setupWorkspace("avail-grant-yes-ws");
        LlmModel granted = seedInstanceModel("gpt-5-granted-yes-upstream", ModelVisibility.GRANTED, true);
        saveGrant(granted.getId(), workspace.getId());

        List<AvailableLlmModelDTO> available = webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/available-models", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<List<AvailableLlmModelDTO>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(available).isNotNull();
        assertThat(available).hasSize(1);
        assertThat(available.get(0).id()).isEqualTo(granted.getId());
    }

    @Test
    @WithAdminUser
    void availableModelsHidesADisabledInstanceModelAndADisabledOwnModel() {
        Workspace workspace = setupWorkspace("avail-disabled-ws");
        seedInstanceModel("gpt-5-disabled-upstream", ModelVisibility.PUBLIC, false);
        WorkspaceLlmConnectionDTO connection = createWorkspaceConnection(workspace, "conn-1");
        WorkspaceLlmModelDTO ownModel = createWorkspaceModel(workspace, connection.id(), "own-model");
        webTestClient
                .patch()
                .uri("/workspaces/{slug}/llm/models/{id}", workspace.getWorkspaceSlug(), ownModel.id())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceLlmModelRequestDTO(
                        null, null, null, null, null, null, null, false, null, null, null, null, null, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        List<AvailableLlmModelDTO> available = webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/available-models", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<List<AvailableLlmModelDTO>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(available).isNotNull();
        assertThat(available).isEmpty();
    }

    @Test
    @WithAdminUser
    void availableModelsResponseNeverContainsUpstreamModelIdBaseUrlOrKeyFields() {
        Workspace workspace = setupWorkspace("avail-redact-ws");
        seedInstanceModel("gpt-5-must-not-leak-upstream", ModelVisibility.PUBLIC, true);
        WorkspaceLlmConnectionDTO connection = createWorkspaceConnection(workspace, "conn-1");
        createWorkspaceModel(workspace, connection.id(), "my-model");

        String body = webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/available-models", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body).doesNotContain("upstreamModelId");
        assertThat(body).doesNotContain("baseUrl");
        assertThat(body).doesNotContain("apiKey");
        assertThat(body).doesNotContain("gpt-5-must-not-leak-upstream");
        assertThat(body).doesNotContain("gpt-5-secret-upstream-id");
        assertThat(body).doesNotContain("sk-workspace-secret");
    }

    @Test
    @WithMentorUser
    void aWorkspaceMemberCannotReachAnyModelEndpoint() {
        Workspace workspace = setupWorkspace("wsmodel-member-ws");
        // Login must match @WithMentorUser's default "mentor" principal so the membership resolver
        // finds this exact row.
        User member = persistUser("mentor");
        ensureWorkspaceMembership(workspace, member, WorkspaceRole.MEMBER);
        String slug = workspace.getWorkspaceSlug();

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/models", slug)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/available-models", slug)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        webTestClient
                .post()
                .uri("/workspaces/{slug}/llm/connections/{connectionId}/models", slug, 1)
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                // A raw map, not the request record: authorization runs before body binding, so the
                // body only has to be well-formed JSON for the 403 to be the assertion that fails first.
                .bodyValue(Map.of("displayName", "Member Model", "upstreamModelId", "gpt-5"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        webTestClient
                .delete()
                .uri("/workspaces/{slug}/llm/models/{id}", slug, 1)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithMentorUser
    void aWorkspaceAdminIsForbiddenInAnotherWorkspaceButStillAdmittedInTheirOwn() {
        // A genuine (non-superadmin) workspace ADMIN — @WithAdminUser carries the instance-wide
        // app_admin elevation, which would let this call through for the wrong reason.
        User admin = persistUser("mentor");
        Workspace own = createWorkspace("wsmodel-own-ws", "Own", "wsmodel-own-org", AccountType.ORG, admin);
        ensureWorkspaceMembership(own, admin, WorkspaceRole.ADMIN);

        Workspace other = createWorkspace(
                "wsmodel-other-ws", "Other", "wsmodel-other-org", AccountType.ORG, persistUser("wsmodel-other-owner"));

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/models", other.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/models", own.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }
}
