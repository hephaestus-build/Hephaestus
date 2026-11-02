package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.exception.ProblemType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;

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
    private LlmCallPoolRepository pools;

    @Autowired
    private LlmModelRepository instanceModels;

    @Autowired
    private LlmModelResolver resolver;

    @Autowired
    private WorkspaceLlmConnectionRepository workspaceConnections;

    @Autowired
    private WorkspaceLlmModelRepository workspaceModels;

    @Test
    @WithAdminUser
    void shouldDescribeTheMembershipArrayReturnedByHttp() throws Exception {
        var first = instanceModel();
        var second = instanceModel();
        var pool = create(slug("array-contract"), 4);
        assertThat(putModels(pool.id(), List.of(first.getId(), second.getId()), List.of()))
                .extracting(LlmCallPoolModelDTO::modelId)
                .contains(first.getId(), second.getId());
        Object specification = new Yaml().load(Files.readString(Path.of("..", "openapi.yaml")));
        var document = new ObjectMapper().valueToTree(specification);
        var schema = document.path("paths")
                .path("/admin/llm/call-pools/{id}/models")
                .path("put")
                .path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("schema");
        assertThat(schema.path("type").asString()).isEqualTo("array");
        assertThat(schema.path("items").path("$ref").asString()).isEqualTo("#/components/schemas/LlmCallPoolModel");
    }

    @Test
    void shouldDocumentBusyPoolAndImmutableSlugResponses() throws Exception {
        Object specification = new Yaml().load(Files.readString(Path.of("..", "openapi.yaml")));
        var paths = new ObjectMapper().valueToTree(specification).path("paths");
        var pool = paths.path("/admin/llm/call-pools/{id}");
        assertThat(pool.path("put").path("responses").has("400")).isTrue();
        assertThat(pool.path("put").path("responses").has("409")).isTrue();
        assertThat(pool.path("delete").path("responses").has("409")).isTrue();
        assertThat(paths.path("/admin/llm/call-pools/{id}/models")
                        .path("put")
                        .path("responses")
                        .has("409"))
                .isTrue();
    }

    @Test
    @WithAdminUser
    void shouldShareAPoolBetweenAnInstanceModelAndAWorkspaceModel() {
        Workspace workspace = workspace("pool-share");
        LlmModel instanceModel = instanceModel();
        WorkspaceLlmModel workspaceModel = workspaceModel(workspace);
        LlmCallPoolDTO pool = create(slug("gpu"), 8);

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
                .uri("/workspaces/{slug}/llm/models", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.id == %d)].callPool.slug".formatted(workspaceModel.getId()))
                .isEqualTo(pool.slug())
                .jsonPath("$[?(@.id == %d)].callPool.maxConcurrentCalls".formatted(workspaceModel.getId()))
                .isEqualTo(8)
                .jsonPath("$[?(@.id == %d)].callPool.learnedLimit".formatted(workspaceModel.getId()))
                .doesNotExist()
                .jsonPath("$[?(@.id == %d)].callPool.noCallBefore".formatted(workspaceModel.getId()))
                .doesNotExist();

        models = putModels(pool.id(), List.of(), List.of(workspaceModel.getId()));

        assertThat(find(models, UsageFundingSource.INSTANCE, instanceModel.getId())
                        .callPoolId())
                .isNotEqualTo(pool.id());
        assertThat(find(models, UsageFundingSource.WORKSPACE, workspaceModel.getId())
                        .callPoolId())
                .isEqualTo(pool.id());
    }

    @Test
    @WithAdminUser
    void shouldMoveAModelWhenAnotherPoolTakesIt() {
        LlmModel model = instanceModel();
        LlmCallPoolDTO first = create(slug("first"), 4);
        LlmCallPoolDTO second = create(slug("second"), 4);
        putModels(first.id(), List.of(model.getId()), List.of());

        List<LlmCallPoolModelDTO> models = putModels(second.id(), List.of(model.getId()), List.of());

        assertThat(find(models, UsageFundingSource.INSTANCE, model.getId()).callPoolId())
                .isEqualTo(second.id());
    }

    @Test
    @WithAdminUser
    void shouldResolveWorkspaceMembershipOnlyForItsWorkspaceAndConnection() {
        var owner = workspace("pool-owner");
        var other = workspace("pool-other");
        var model = workspaceModel(owner);
        var pool = create(slug("scoped"), 4);
        putModels(pool.id(), List.of(), List.of(model.getId()));
        long connectionId = model.getConnection().getId();
        var scope = UsageFundingSource.WORKSPACE;
        assertThat(resolver.resolveCallPool(
                        new LlmModelResolver.ConnectionRef(scope, connectionId, model.getId(), owner.getId())))
                .isEqualTo(pool.id());
        assertThat(resolver.resolveCallPool(
                        new LlmModelResolver.ConnectionRef(scope, connectionId, model.getId(), other.getId())))
                .isNull();
        assertThat(resolver.resolveCallPool(
                        new LlmModelResolver.ConnectionRef(scope, Long.MAX_VALUE, model.getId(), owner.getId())))
                .isNull();
    }

    @Test
    @WithAdminUser
    void shouldKeepTheModelsWhenOneOfTheChosenModelsDoesNotExist() {
        LlmModel model = instanceModel();
        LlmCallPoolDTO pool = create(slug("keep"), 4);
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
    void shouldGiveEachModelItsOwnPoolAgainWhenTheSharedPoolIsDeleted() {
        LlmModel model = instanceModel();
        LlmCallPoolDTO pool = create(slug("gone"), 4);
        putModels(pool.id(), List.of(model.getId()), List.of());

        webTestClient
                .delete()
                .uri("/admin/llm/call-pools/{id}", pool.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        long own = instanceModels.findById(model.getId()).orElseThrow().getCallPoolId();
        assertThat(own).isNotEqualTo(pool.id());
        assertThat(pools.findById(own).orElseThrow().isDedicated()).isTrue();
    }

    @Test
    @WithAdminUser
    void shouldGiveEveryModelItsOwnPoolWithNoAdminAndEndItWithTheModel() {
        Workspace workspace = workspace("pool-own");
        LlmModel instance = instanceModel();
        WorkspaceLlmModel own = workspaceModel(workspace);
        long instancePool = instance.getCallPoolId();
        long workspacePool = own.getCallPoolId();

        var row = pools.findById(workspacePool).orElseThrow();
        assertThat(row.isDedicated()).isTrue();
        assertThat(row.getMaxConcurrentCalls()).isNull();
        assertThat(row.getLearnedLimit()).isEqualTo(8);
        assertThat(row.getSlug()).isEqualTo("own-workspace-model-" + own.getId());
        assertThat(pools.findById(instancePool).orElseThrow().getSlug()).isEqualTo("own-model-" + instance.getId());

        instanceModels.delete(instance);
        workspaceModels.delete(own);

        assertThat(pools.findById(instancePool)).isEmpty();
        assertThat(pools.findById(workspacePool)).isEmpty();
    }

    @Test
    @WithAdminUser
    void shouldEndAModelsOwnPoolWhenItJoinsASharedOneAndGiveItANewOneWhenItLeaves() {
        LlmModel model = instanceModel();
        long own = model.getCallPoolId();
        LlmCallPoolDTO shared = create(slug("join"), 4);

        putModels(shared.id(), List.of(model.getId()), List.of());
        assertThat(pools.findById(own)).isEmpty();

        var models = putModels(shared.id(), List.of(), List.of());
        long fresh = find(models, UsageFundingSource.INSTANCE, model.getId()).callPoolId();
        assertThat(fresh).isNotEqualTo(shared.id());
        assertThat(pools.findById(fresh).orElseThrow().isDedicated()).isTrue();
    }

    @Test
    @WithAdminUser
    void shouldKeepAModelsOwnPoolToThatModel() {
        LlmModel first = instanceModel();
        LlmModel second = instanceModel();
        long own = first.getCallPoolId();

        webTestClient
                .delete()
                .uri("/admin/llm/call-pools/{id}", own)
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        webTestClient
                .put()
                .uri("/admin/llm/call-pools/{id}/models", own)
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateLlmCallPoolModelsRequestDTO(List.of(second.getId()), List.of()))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        assertThat(instanceModels.findById(second.getId()).orElseThrow().getCallPoolId())
                .isNotEqualTo(own);
    }

    @Test
    @WithAdminUser
    void shouldLetAnAdminSetACeilingOnAModelsOwnPoolAndTakeItBack() {
        LlmModel model = instanceModel();
        var row = pools.findById(model.getCallPoolId()).orElseThrow();

        LlmCallPoolDTO capped = put(row.getId(), row.getSlug(), 4);
        assertThat(capped.maxConcurrentCalls()).isEqualTo(4);
        assertThat(capped.learnedLimit()).isEqualTo(8);
        assertThat(capped.effectiveLimit()).isEqualTo(4);
        assertThat(capped.conversationReserve()).isEqualTo(1);
        assertThat(capped.dedicated()).isTrue();

        LlmCallPoolDTO uncapped = put(row.getId(), row.getSlug(), null);
        assertThat(uncapped.maxConcurrentCalls()).isNull();
        assertThat(uncapped.effectiveLimit()).isEqualTo(8);
        assertThat(uncapped.conversationReserve()).isEqualTo(2);
    }

    private LlmCallPoolDTO put(long id, String slug, @Nullable Integer ceiling) {
        return Objects.requireNonNull(webTestClient
                .put()
                .uri("/admin/llm/call-pools/{id}", id)
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveLlmCallPoolRequestDTO(slug, "Own", ceiling, null, null, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(LlmCallPoolDTO.class)
                .returnResult()
                .getResponseBody());
    }

    @Test
    @WithAdminUser
    void shouldChangeTheLimitsButNotTheSlug() {
        LlmCallPoolDTO pool = create(slug("change"), 4);

        LlmCallPoolDTO changed = Objects.requireNonNull(webTestClient
                .put()
                .uri("/admin/llm/call-pools/{id}", pool.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveLlmCallPoolRequestDTO(pool.slug(), "Renamed", null, null, null, null))
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
    void shouldRefuseABadSlugAndATokenRateWithoutABound() {
        refuseCreate(new SaveLlmCallPoolRequestDTO("Not A Slug", "Bad slug", 2, null, null, null));
        refuseCreate(new SaveLlmCallPoolRequestDTO(slug("no-bound"), "No bound", 2, null, 100L, null));
        refuseCreate(new SaveLlmCallPoolRequestDTO(slug("over-rate"), "Over rate", 2, null, 100L, 101L));
        refuseCreate(new SaveLlmCallPoolRequestDTO("own-" + slug("reserved"), "Reserved prefix", 2, null, null, null));
    }

    @Test
    @WithAdminUser
    void shouldRefuseASlugThatIsTaken() {
        String slug = slug("taken");
        create(slug, 4);

        webTestClient
                .post()
                .uri("/admin/llm/call-pools")
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveLlmCallPoolRequestDTO(slug, "Again", 4, null, null, null))
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
                .bodyValue(new SaveLlmCallPoolRequestDTO(slug("denied"), "Denied", 4, null, null, null))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldAllowMembershipEditsWhileAnUnrelatedPoolIsBusy() {
        var busy = create(slug("busy"), 4);
        var idle = create(slug("idle"), 4);
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
        var pool = create(slug("closing"), 4);
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
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(ProblemType.LLM_CALL_POOL_IN_USE.uri().toString())
                .jsonPath("$.detail")
                .value(detail -> assertThat(String.valueOf(detail)).contains("rate window"));
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
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(ProblemType.LLM_CALL_POOL_IN_USE.uri().toString())
                .jsonPath("$.detail")
                .value(detail -> assertThat(String.valueOf(detail)).contains("rate window"));
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
        var pool = create(slug("expired-waiter"), 4);
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
                .isNoContent()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldAllowEditingAndDeletingAfterACrashedOwnersLeaseAndRateWindowExpire() {
        var pool = create(slug("crashed-owner"), 4);
        jdbc.update(
                "INSERT INTO llm_call_permit (id,pool_id,worker_id,call_class,workspace_id,source_id,attempt,state,acquired_at,renewed_at,granted_at,requests,tokens) VALUES (?,?,?, 'EVENT',1,?,0,'ACTIVE',clock_timestamp()-interval '1 hour',clock_timestamp()-interval '1 hour',clock_timestamp()-interval '1 hour',1,17)",
                UUID.randomUUID(),
                pool.id(),
                "crashed-worker",
                UUID.randomUUID());
        webTestClient
                .put()
                .uri("/admin/llm/call-pools/{id}", pool.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveLlmCallPoolRequestDTO(pool.slug(), "Recovered endpoint", 3, null, null, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.displayName")
                .isEqualTo("Recovered endpoint");
        webTestClient
                .delete()
                .uri("/admin/llm/call-pools/{id}", pool.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);
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

    private LlmCallPoolDTO create(String slug, @Nullable Integer max) {
        return Objects.requireNonNull(webTestClient
                .post()
                .uri("/admin/llm/call-pools")
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveLlmCallPoolRequestDTO(slug, "Shared GPU", max, null, null, null))
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
