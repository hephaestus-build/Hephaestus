package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class LlmModelAdminControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String ADMIN_TOKEN = "mock-jwt-token-for-admin-user";
    private static final String MENTOR_TOKEN = "mock-jwt-token-for-mentor-user";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private LlmConnectionRepository llmConnectionRepository;

    @Autowired
    private WorkspaceAgentBindingRepository agentBindingRepository;

    @Autowired
    private LlmModelRepository llmModelRepository;

    private LlmConnection seedConnection() {
        return llmConnectionRepository.save(LlmCatalogTestFixtures.connection("conn-" + System.nanoTime()));
    }

    private LlmModelDTO createModel(Long connectionId, String slug) {
        // Models start inactive until an explicit price declaration is supplied.
        var request = new CreateLlmModelRequestDTO(slug, "Test Model", "gpt-5", null, null, null, null, false);
        return Objects.requireNonNull(webTestClient
                .post()
                .uri("/admin/llm/connections/{connectionId}/models", connectionId)
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(LlmModelDTO.class)
                .returnResult()
                .getResponseBody());
    }

    @Test
    void appAdminCanCreateGetListUpdateAndDeleteAModel() {
        LlmConnection connection = seedConnection();
        LlmModelDTO created = createModel(connection.getId(), "gpt-5-eu");
        assertThat(created).isNotNull();
        assertThat(created.slug()).isEqualTo("gpt-5-eu");
        assertThat(created.visibility()).isEqualTo(ModelVisibility.GRANTED);
        assertThat(created.currentPrice()).isNull();

        webTestClient
                .get()
                .uri("/admin/llm/models/{id}", created.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.slug")
                .isEqualTo("gpt-5-eu");

        webTestClient
                .get()
                .uri("/admin/llm/models")
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1);

        var updateRequest = new UpdateLlmModelRequestDTO("Renamed Model", null, null, null, null, null);
        webTestClient
                .patch()
                .uri("/admin/llm/models/{id}", created.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
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
                .uri("/admin/llm/models/{id}", created.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        webTestClient
                .get()
                .uri("/admin/llm/models/{id}", created.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void repricingTwiceSupersedesAndGetReturnsOnlyTheCurrentPrice() {
        LlmConnection connection = seedConnection();
        LlmModelDTO model = createModel(connection.getId(), "reprice-model");

        var firstPrice = new UpdateLlmModelPriceRequestDTO(
                PricingMode.PRICED, new BigDecimal("1.00"), new BigDecimal("2.00"), null, null, null);
        webTestClient
                .put()
                .uri("/admin/llm/models/{id}/price", model.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(firstPrice)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        var secondPrice = new UpdateLlmModelPriceRequestDTO(
                PricingMode.PRICED, new BigDecimal("3.00"), new BigDecimal("4.00"), null, null, null);
        LlmModelDTO afterSecondPrice = webTestClient
                .put()
                .uri("/admin/llm/models/{id}/price", model.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(secondPrice)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(LlmModelDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(afterSecondPrice).isNotNull();
        assertThat(afterSecondPrice.currentPrice()).isNotNull();
        assertThat(afterSecondPrice.currentPrice().per1mInputUsd()).isEqualByComparingTo("3.00");
        assertThat(afterSecondPrice.currentPrice().per1mOutputUsd()).isEqualByComparingTo("4.00");

        LlmModelDTO fetched = webTestClient
                .get()
                .uri("/admin/llm/models/{id}", model.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(LlmModelDTO.class)
                .returnResult()
                .getResponseBody();
        assertThat(fetched).isNotNull();
        assertThat(Objects.requireNonNull(fetched.currentPrice()).per1mInputUsd())
                .isEqualByComparingTo("3.00");
    }

    @Test
    void sharingWithASelectedWorkspaceSetRoundTrips() {
        LlmConnection connection = seedConnection();
        LlmModelDTO model = createModel(connection.getId(), "sharing-model");

        User owner = persistUser("sharing-owner");
        Workspace workspaceA = createWorkspace("sharing-ws-a", "Sharing A", "sharing-org-a", AccountType.ORG, owner);
        User ownerB = persistUser("sharing-owner-b");
        Workspace workspaceB = createWorkspace("sharing-ws-b", "Sharing B", "sharing-org-b", AccountType.ORG, ownerB);

        var grantRequest = new UpdateLlmModelSharingRequestDTO(ModelVisibility.GRANTED, List.of(workspaceA.getId()));
        LlmModelDTO afterGrant = webTestClient
                .put()
                .uri("/admin/llm/models/{id}/sharing", model.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(grantRequest)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(LlmModelDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(afterGrant).isNotNull();
        assertThat(afterGrant.visibility()).isEqualTo(ModelVisibility.GRANTED);
        assertThat(afterGrant.grantedWorkspaceIds()).containsExactly(workspaceA.getId());
        assertThat(afterGrant.grantedWorkspaceIds()).doesNotContain(workspaceB.getId());

        var replaceRequest = new UpdateLlmModelSharingRequestDTO(ModelVisibility.GRANTED, List.of(workspaceB.getId()));
        LlmModelDTO afterReplace = webTestClient
                .put()
                .uri("/admin/llm/models/{id}/sharing", model.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(replaceRequest)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(LlmModelDTO.class)
                .returnResult()
                .getResponseBody();
        assertThat(afterReplace).isNotNull();
        assertThat(afterReplace.grantedWorkspaceIds()).containsExactly(workspaceB.getId());

        var publicRequest = new UpdateLlmModelSharingRequestDTO(ModelVisibility.PUBLIC, null);
        LlmModelDTO afterPublic = webTestClient
                .put()
                .uri("/admin/llm/models/{id}/sharing", model.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(publicRequest)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(LlmModelDTO.class)
                .returnResult()
                .getResponseBody();
        assertThat(afterPublic).isNotNull();
        assertThat(afterPublic.visibility()).isEqualTo(ModelVisibility.PUBLIC);
        assertThat(afterPublic.grantedWorkspaceIds()).isEmpty();
    }

    @Test
    void deletingAModelBoundToAWorkspaceBindingReturns409() {
        LlmConnection connection = seedConnection();
        LlmModelDTO model = createModel(connection.getId(), "bound-model");

        User owner = persistUser("delete-guard-owner");
        Workspace workspace =
                createWorkspace("delete-guard-ws", "Delete Guard", "delete-guard-org", AccountType.ORG, owner);
        WorkspaceAgentBinding binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setInstanceModel(llmModelFromRepository(model.id()));
        agentBindingRepository.save(binding);

        webTestClient
                .delete()
                .uri("/admin/llm/models/{id}", model.id())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
    }

    @Test
    void aDuplicateUpstreamModelIdOnTheSameConnectionIs409WithAProblemDetail() {
        // The uniqueness this defends is a billing invariant: two catalog rows for one upstream id let
        // LlmUsageRecorder match either, so a NO_CHARGE sibling can silently shadow a PRICED one.
        //
        // The status is asserted because @ResponseStatus(CONFLICT) on the exception cannot deliver it:
        // GlobalControllerAdvice's @ExceptionHandler(Exception.class) already matches, so
        // ExceptionHandlerExceptionResolver wins and ResponseStatusExceptionResolver never runs. Without
        // an explicit handler in AgentControllerAdvice this answers 500 while OpenAPI promises 409.
        LlmConnection connection = seedConnection();
        createModel(connection.getId(), "dup-first");

        webTestClient
                .post()
                .uri("/admin/llm/connections/{connectionId}/models", connection.getId())
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                // Different slug, SAME upstream model id as "dup-first" — only the upstream-id guard can
                // reject this, so a pass cannot be the slug-conflict handler answering by accident.
                .bodyValue(new CreateLlmModelRequestDTO(
                        "dup-second", "Test Model", "gpt-5", null, null, null, null, false))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(409)
                .jsonPath("$.title")
                .isEqualTo("LLM model upstream id conflict")
                .jsonPath("$.detail")
                .value(detail -> assertThat((String) detail).contains("gpt-5"));
    }

    @Test
    void nonAdminIsForbidden() {
        webTestClient
                .get()
                .uri("/admin/llm/models")
                .headers(h -> h.setBearerAuth(MENTOR_TOKEN))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    private LlmModel llmModelFromRepository(Long id) {
        return llmModelRepository.findById(id).orElseThrow();
    }
}
