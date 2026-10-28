package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

class LlmCallPoolAdminControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String ADMIN_TOKEN = "mock-jwt-token-for-admin-user";
    private static final String MENTOR_TOKEN = "mock-jwt-token-for-mentor-user";

    @Autowired
    private WebTestClient webTestClient;

    @BeforeEach
    void setUpAdminIdentity() {
        persistUser("admin");
    }

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LlmModelRepository instanceModels;

    @Autowired
    private WorkspaceLlmConnectionRepository workspaceConnections;

    @Autowired
    private WorkspaceLlmModelRepository workspaceModels;

    @Test
    @WithAdminUser
    void shouldShareAPoolBetweenAnInstanceModelAndAWorkspaceModel() {
        Workspace workspace = workspace("pool-share");
        LlmModel instanceModel = instanceModel();
        WorkspaceLlmModel workspaceModel = workspaceModel(workspace);
        LlmCallPoolDTO pool = create(slug("gpu"), 8, 2);

        List<LlmCallPoolModelDTO> models =
                putModels(pool.id(), List.of(instanceModel.getId()), List.of(workspaceModel.getId()));

        LlmCallPoolModelDTO listedWorkspaceModel = find(models, UsageFundingSource.WORKSPACE, workspaceModel.getId());
        assertThat(listedWorkspaceModel.callPoolId()).isEqualTo(pool.id());
        assertThat(listedWorkspaceModel.workspaceSlug()).isEqualTo(workspace.getWorkspaceSlug());
        assertThat(listedWorkspaceModel.host()).isEqualTo("8.8.8.8");
        assertThat(find(models, UsageFundingSource.INSTANCE, instanceModel.getId())
                        .callPoolId())
                .isEqualTo(pool.id());
        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/models/{id}", workspace.getWorkspaceSlug(), workspaceModel.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.callPool.slug")
                .isEqualTo(pool.slug())
                .jsonPath("$.callPool.interactiveReserve")
                .isEqualTo(2)
                .jsonPath("$.callPool.noCallBefore")
                .doesNotExist();

        models = putModels(pool.id(), List.of(), List.of(workspaceModel.getId()));

        assertThat(find(models, UsageFundingSource.INSTANCE, instanceModel.getId())
                        .callPoolId())
                .isNull();
        assertThat(find(models, UsageFundingSource.WORKSPACE, workspaceModel.getId())
                        .callPoolId())
                .isEqualTo(pool.id());
    }

    @Test
    @WithAdminUser
    void shouldMoveAModelWhenAnotherPoolTakesIt() {
        LlmModel model = instanceModel();
        LlmCallPoolDTO first = create(slug("first"), 4, 0);
        LlmCallPoolDTO second = create(slug("second"), 4, 0);
        putModels(first.id(), List.of(model.getId()), List.of());

        List<LlmCallPoolModelDTO> models = putModels(second.id(), List.of(model.getId()), List.of());

        assertThat(find(models, UsageFundingSource.INSTANCE, model.getId()).callPoolId())
                .isEqualTo(second.id());
    }

    @Test
    @WithAdminUser
    void shouldKeepTheModelsWhenOneOfTheChosenModelsDoesNotExist() {
        LlmModel model = instanceModel();
        LlmCallPoolDTO pool = create(slug("keep"), 4, 0);
        putModels(pool.id(), List.of(model.getId()), List.of());

        webTestClient
                .put()
                .uri("/admin/llm/call-pools/{id}/models", pool.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateLlmCallPoolModelsRequestDTO(List.of(Long.MAX_VALUE), List.of()))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);

        assertThat(instanceModels.findById(model.getId()).orElseThrow().getCallPoolId())
                .isEqualTo(pool.id());
    }

    @Test
    @WithAdminUser
    void shouldLeaveTheModelsUnlimitedWhenThePoolIsDeleted() {
        LlmModel model = instanceModel();
        LlmCallPoolDTO pool = create(slug("gone"), 4, 0);
        putModels(pool.id(), List.of(model.getId()), List.of());

        webTestClient
                .delete()
                .uri("/admin/llm/call-pools/{id}", pool.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        assertThat(instanceModels.findById(model.getId()).orElseThrow().getCallPoolId())
                .isNull();
    }

    @Test
    @WithAdminUser
    void shouldChangeTheLimitsButNotTheSlug() {
        LlmCallPoolDTO pool = create(slug("change"), 4, 1);

        LlmCallPoolDTO changed = Objects.requireNonNull(webTestClient
                .put()
                .uri("/admin/llm/call-pools/{id}", pool.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveLlmCallPoolRequestDTO(pool.slug(), "Renamed", null, 0, null, null, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(LlmCallPoolDTO.class)
                .returnResult()
                .getResponseBody());

        assertThat(changed.slug()).isEqualTo(pool.slug());
        assertThat(changed.displayName()).isEqualTo("Renamed");
        assertThat(changed.maxConcurrentCalls()).isNull();
    }

    @Test
    @WithAdminUser
    void shouldRefuseAReserveTheMaximumCannotHold() {
        refuseCreate(new SaveLlmCallPoolRequestDTO(slug("over"), "Over", 2, 3, null, null, null));
        refuseCreate(new SaveLlmCallPoolRequestDTO(slug("nomax"), "No maximum", null, 1, null, null, null));
        refuseCreate(new SaveLlmCallPoolRequestDTO("Not A Slug", "Bad slug", 2, 0, null, null, null));
    }

    @Test
    @WithAdminUser
    void shouldRefuseASlugThatIsTaken() {
        String slug = slug("taken");
        create(slug, 4, 0);

        webTestClient
                .post()
                .uri("/admin/llm/call-pools")
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveLlmCallPoolRequestDTO(slug, "Again", 4, 0, null, null, null))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.type")
                .value(type -> assertThat(String.valueOf(type)).endsWith("llm-call-pool-slug-conflict"));
    }

    @Test
    void shouldRefuseAnyoneButTheInstanceAdmin() {
        persistUser("mentor");
        webTestClient
                .post()
                .uri("/admin/llm/call-pools")
                .headers(h -> h.setBearerAuth(MENTOR_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveLlmCallPoolRequestDTO(slug("denied"), "Denied", 4, 0, null, null, null))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldAllowMembershipEditsWhileAnUnrelatedPoolIsBusy() {
        var busy = create(slug("busy"), 4, 1);
        var idle = create(slug("idle"), 4, 1);
        jdbc.update(
                "INSERT INTO llm_call_permit (id,pool_id,worker_id,call_class,workspace_id,source_id,attempt,state,acquired_at,renewed_at,requests,tokens) VALUES (?,?,?, 'EVENT',1,?,0,'ACTIVE',clock_timestamp(),clock_timestamp(),1,0)",
                UUID.randomUUID(),
                busy.id(),
                "busy-worker",
                UUID.randomUUID());
        var model = instanceModel();
        var assigned = putModels(idle.id(), List.of(model.getId()), List.of());
        assertThat(find(assigned, UsageFundingSource.INSTANCE, model.getId()).callPoolId())
                .isEqualTo(idle.id());
    }

    @Test
    @WithAdminUser
    void shouldRefuseToDeleteAPoolBeforeItsCallAndRateWindowClose() {
        var pool = create(slug("closing"), 4, 1);
        UUID call = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO llm_call_permit (id,pool_id,worker_id,call_class,workspace_id,source_id,attempt,state,acquired_at,renewed_at,requests,tokens) VALUES (?,?,?, 'EVENT',1,?,0,'ACTIVE',clock_timestamp(),clock_timestamp(),1,0)",
                call,
                pool.id(),
                "closing-worker",
                UUID.randomUUID());
        webTestClient
                .delete()
                .uri("/admin/llm/call-pools/{id}", pool.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        jdbc.update(
                "UPDATE llm_call_permit SET state='CLOSED',granted_at=clock_timestamp(),closed_at=clock_timestamp() WHERE id=?",
                call);
        webTestClient
                .delete()
                .uri("/admin/llm/call-pools/{id}", pool.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        jdbc.update("UPDATE llm_call_permit SET closed_at=clock_timestamp()-interval '61 seconds' WHERE id=?", call);
        webTestClient
                .delete()
                .uri("/admin/llm/call-pools/{id}", pool.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldRemoveAnExpiredWaiterWhenDeletingAnIdlePool() {
        var pool = create(slug("expired-waiter"), 4, 1);
        jdbc.update(
                "INSERT INTO llm_call_permit (id,pool_id,worker_id,call_class,workspace_id,source_id,attempt,state,acquired_at,renewed_at,requests,tokens) VALUES (?,?,?, 'EVENT',1,?,0,'WAITING',clock_timestamp()-interval '1 hour',clock_timestamp()-interval '1 hour',0,0)",
                UUID.randomUUID(),
                pool.id(),
                "lost-waiter",
                UUID.randomUUID());
        webTestClient
                .delete()
                .uri("/admin/llm/call-pools/{id}", pool.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    private static String slug(String name) {
        return name + "-" + System.nanoTime();
    }

    private Workspace workspace(String slug) {
        User owner = persistUser(slug + "-owner");
        Workspace workspace = createWorkspace(slug, "Workspace " + slug, slug + "-org", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        return workspace;
    }

    private LlmModel instanceModel() {
        LlmConnection connection =
                connections.save(LlmCatalogTestFixtures.connection("pool-conn-" + System.nanoTime()));
        return instanceModels.save(
                LlmCatalogTestFixtures.model(connection, "pool-model-" + System.nanoTime(), "gpt-5"));
    }

    private WorkspaceLlmModel workspaceModel(Workspace workspace) {
        WorkspaceLlmConnection connection = new WorkspaceLlmConnection();
        connection.setWorkspace(workspace);
        connection.setSlug("pool-ws-conn-" + System.nanoTime());
        connection.setDisplayName("Course gateway");
        connection.setBaseUrl(LlmCatalogTestFixtures.PUBLIC_BASE_URL);
        connection.setApiProtocol(LlmApiProtocol.OPENAI_COMPLETIONS);
        connection.setEnabled(true);
        connection = workspaceConnections.save(connection);
        WorkspaceLlmModel model = new WorkspaceLlmModel();
        model.setWorkspace(workspace);
        model.setConnection(connection);
        model.setSlug("pool-ws-model-" + System.nanoTime());
        model.setDisplayName("Course model");
        model.setUpstreamModelId("course-model-" + System.nanoTime());
        return workspaceModels.save(model);
    }

    private LlmCallPoolDTO create(String slug, @Nullable Integer max, int reserve) {
        return Objects.requireNonNull(webTestClient
                .post()
                .uri("/admin/llm/call-pools")
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveLlmCallPoolRequestDTO(slug, "Shared GPU", max, reserve, null, null, null))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(LlmCallPoolDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private void refuseCreate(SaveLlmCallPoolRequestDTO request) {
        webTestClient
                .post()
                .uri("/admin/llm/call-pools")
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    private List<LlmCallPoolModelDTO> putModels(long poolId, List<Long> instanceIds, List<Long> workspaceIds) {
        return Objects.requireNonNull(webTestClient
                .put()
                .uri("/admin/llm/call-pools/{id}/models", poolId)
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateLlmCallPoolModelsRequestDTO(instanceIds, workspaceIds))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<List<LlmCallPoolModelDTO>>() {})
                .returnResult()
                .getResponseBody());
    }

    private static LlmCallPoolModelDTO find(List<LlmCallPoolModelDTO> models, UsageFundingSource scope, Long id) {
        return models.stream()
                .filter(model -> model.scope() == scope && model.modelId().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
