package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.catalog.DataHandlingFacts;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmDataOperator;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.core.exception.ProblemType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithConversationUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Exists mainly for the listing: readiness is judged outside the loading transaction because resolve()
 * signals a revoked model by throwing, and catching that inside a shared transaction would still mark it
 * rollback-only. A plain finder returns rows whose LAZY model blows up on first touch outside a session,
 * a boundary mocked repositories never cross.
 */
class ModelAssignmentControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private LlmConnectionRepository llmConnectionRepository;

    @Autowired
    private LlmModelRepository llmModelRepository;

    private Workspace setupWorkspace(String slug) {
        User owner = persistUser(slug + "-owner");
        Workspace workspace = createWorkspace(slug, "Binding Workspace", slug + "-org", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        return workspace;
    }

    private LlmModel seedInstanceModel(String slug) {
        LlmConnection connection = llmConnectionRepository.save(LlmCatalogTestFixtures.connection(slug));
        return llmModelRepository.save(LlmCatalogTestFixtures.model(connection, slug + "-model", "gpt-binding-test"));
    }

    @Test
    @WithAdminUser
    @DisplayName("listing bindings reports readiness without tripping over the bound model's lazy associations")
    void listingReportsReadinessForAFreshlyLoadedBinding() {
        Workspace workspace = setupWorkspace("binding-list");
        LlmModel model = seedInstanceModel("binding-list");

        webTestClient
                .put()
                .uri("/workspaces/{slug}/model-assignments/{purpose}", workspace.getWorkspaceSlug(), "PRACTICE_REVIEW")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("instanceModelId", model.getId(), "enabled", true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        // A separate request, so the listing loads the binding fresh: the write above cannot have left
        // it hydrated in a session.
        webTestClient
                .get()
                .uri("/workspaces/{slug}/model-assignments", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].purpose")
                .isEqualTo("PRACTICE_REVIEW")
                .jsonPath("$[0].instanceModelId")
                .isEqualTo(model.getId())
                .jsonPath("$[0].ready")
                .isEqualTo(true);
    }

    @Test
    @WithAdminUser
    @DisplayName("each binding names the members it serves, judged at each member's own ceiling")
    void shouldNameTheMembersEachBindingServesWhenBindingsHaveDifferentTiers() {
        Workspace workspace = setupWorkspace("binding-served");
        LlmModel review = seedInstanceModel("binding-served-review");
        review.setDataHandling(DataHandlingFacts.of(LlmDataOperator.OWN_ORGANISATION, null));
        llmModelRepository.save(review);
        LlmModel conversation = seedInstanceModel("binding-served-conversation");
        conversation.setDataHandling(DataHandlingFacts.of(LlmDataOperator.PROVIDER, null));
        conversation = llmModelRepository.save(conversation);

        assign(workspace, "PRACTICE_REVIEW", "IN_HOUSE", review)
                .jsonPath("$.servedTiers")
                .isEqualTo(List.of("IN_HOUSE", "CLOUD"));
        // A cloud model is above the ceiling of members who keep their work in-house.
        assign(workspace, "CONVERSATION", "CLOUD", conversation)
                .jsonPath("$.servedTiers")
                .isEqualTo(List.of("CLOUD"));

        webTestClient
                .get()
                .uri("/workspaces/{slug}/model-assignments", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.purpose == 'PRACTICE_REVIEW')].servedTiers")
                .isEqualTo(List.of(List.of("IN_HOUSE", "CLOUD")))
                .jsonPath("$[?(@.purpose == 'CONVERSATION')].servedTiers")
                .isEqualTo(List.of(List.of("CLOUD")));
    }

    private WebTestClient.BodyContentSpec assign(Workspace workspace, String purpose, String tier, LlmModel model) {
        return webTestClient
                .put()
                .uri(
                        "/workspaces/{slug}/model-assignments/{purpose}?dataHandlingTier={tier}",
                        workspace.getWorkspaceSlug(),
                        purpose,
                        tier)
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("instanceModelId", model.getId(), "enabled", true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    @Test
    @WithAdminUser
    @DisplayName("a disabled binding is listed but not ready, and deleting it turns the purpose off")
    void disabledBindingIsNotReadyAndCanBeDeleted() {
        Workspace workspace = setupWorkspace("binding-off");
        LlmModel model = seedInstanceModel("binding-off");

        webTestClient
                .put()
                .uri("/workspaces/{slug}/model-assignments/{purpose}", workspace.getWorkspaceSlug(), "CONVERSATION")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("instanceModelId", model.getId(), "enabled", false))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/model-assignments", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].enabled")
                .isEqualTo(false)
                .jsonPath("$[0].ready")
                .isEqualTo(false);

        webTestClient
                .delete()
                .uri("/workspaces/{slug}/model-assignments/{purpose}", workspace.getWorkspaceSlug(), "CONVERSATION")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/model-assignments", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(0);
    }

    @Test
    @WithAdminUser
    @DisplayName("binding a model the workspace cannot use is rejected")
    void bindingAnUnavailableModelIsRejected() {
        Workspace workspace = setupWorkspace("binding-bad");
        LlmModel model = seedInstanceModel("binding-bad");
        model.setEnabled(false);
        llmModelRepository.save(model);

        webTestClient
                .put()
                .uri("/workspaces/{slug}/model-assignments/{purpose}", workspace.getWorkspaceSlug(), "PRACTICE_REVIEW")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("instanceModelId", model.getId(), "enabled", true))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    @DisplayName("a tier row refuses a model declared as another tier and names that tier for the client")
    void bindingAModelDeclaredAsAnotherTierIsRefusedWithTheDeclaredTier() {
        Workspace workspace = setupWorkspace("binding-slot");
        LlmModel model = seedInstanceModel("binding-slot");
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.OWN_ORGANISATION, null));
        llmModelRepository.save(model);

        webTestClient
                .put()
                .uri(
                        "/workspaces/{slug}/model-assignments/{purpose}?dataHandlingTier=CLOUD",
                        workspace.getWorkspaceSlug(),
                        "PRACTICE_REVIEW")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("instanceModelId", model.getId(), "enabled", true))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(ProblemType.MODEL_ASSIGNMENT_SLOT_MISMATCH.uri().toString())
                .jsonPath("$.declaredTier")
                .isEqualTo("IN_HOUSE")
                .jsonPath("$.detail")
                .isEqualTo("This model is declared as a different tier. Assign it to that row.");
    }

    @Test
    @WithAdminUser
    @DisplayName("a tier row refuses an undeclared model without claiming it is declared as anything")
    void bindingAnUndeclaredModelToATierRowIsRefused() {
        Workspace workspace = setupWorkspace("binding-undeclared");
        LlmModel model = seedInstanceModel("binding-undeclared");

        webTestClient
                .put()
                .uri(
                        "/workspaces/{slug}/model-assignments/{purpose}?dataHandlingTier=IN_HOUSE",
                        workspace.getWorkspaceSlug(),
                        "CONVERSATION")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("instanceModelId", model.getId(), "enabled", true))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(ProblemType.MODEL_ASSIGNMENT_SLOT_MISMATCH.uri().toString())
                .jsonPath("$.declaredTier")
                .doesNotExist()
                .jsonPath("$.detail")
                .isEqualTo("This model’s data handling is not declared yet. "
                        + "Declare it first, or assign it to Members who have not chosen.");
    }

    @Test
    @DisplayName("the endpoints require authentication")
    void anonymousIsRejected() {
        // SecurityConfig permits anonymous GET under a workspace slug, so @RequireAtLeastWorkspaceAdmin
        // on listModelAssignments is pinned here and nowhere else. Against a REAL workspace: an unknown slug 404s
        // during resolution, which would pass this assertion without reaching the authentication check.
        User owner = persistUser("binding-anon-owner");
        Workspace workspace =
                createWorkspace("binding-anon", "Binding Workspace", "binding-anon-org", AccountType.ORG, owner);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/model-assignments", workspace.getWorkspaceSlug())
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    @WithConversationUser
    @DisplayName("a plain member cannot configure an agent")
    void aWorkspaceMemberCannotPutABinding() {
        Workspace workspace = setupWorkspace("binding-member");
        // Login must match @WithConversationUser's default "conversation" principal so the membership resolver
        // finds this exact row.
        User member = persistUser("conversation");
        ensureMembership(workspace, member, WorkspaceRole.MEMBER);

        webTestClient
                .put()
                .uri(
                        "/workspaces/{slug}/model-assignments/{purpose}",
                        workspace.getWorkspaceSlug(),
                        ModelPurpose.PRACTICE_REVIEW)
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "instanceModelId",
                        seedInstanceModel("binding-member-model").getId(),
                        "enabled",
                        true))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithConversationUser
    @DisplayName("a plain member cannot turn an agent off")
    void aWorkspaceMemberCannotDeleteABinding() {
        Workspace workspace = setupWorkspace("binding-member-del");
        User member = persistUser("conversation");
        ensureMembership(workspace, member, WorkspaceRole.MEMBER);

        webTestClient
                .delete()
                .uri(
                        "/workspaces/{slug}/model-assignments/{purpose}",
                        workspace.getWorkspaceSlug(),
                        ModelPurpose.PRACTICE_REVIEW)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithConversationUser
    @DisplayName("an admin of another workspace cannot configure this workspace's agents")
    void aWorkspaceAdminWithNoMembershipHereGetsNotFound() {
        // A genuine (non-superadmin) workspace ADMIN. @WithAdminUser would carry the instance-wide
        // app_admin elevation and pass this call for the wrong reason.
        User admin = persistUser("conversation");
        Workspace own = createWorkspace("binding-own-ws", "Own", "binding-own-org", AccountType.ORG, admin);
        ensureMembership(own, admin, WorkspaceRole.ADMIN);

        Workspace other = createWorkspace(
                "binding-other-ws", "Other", "binding-other-org", AccountType.ORG, persistUser("binding-other-owner"));

        webTestClient
                .delete()
                .uri(
                        "/workspaces/{slug}/model-assignments/{purpose}",
                        other.getWorkspaceSlug(),
                        ModelPurpose.PRACTICE_REVIEW)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/model-assignments", own.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }
}
