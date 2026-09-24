package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.StubMentorChatStarter;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Fires the real filter chain so the SSE endpoint's two guards — WorkspaceContextFilter
 * membership pre-check and the {@code @PreAuthorize("@workspaceSecure.isMember()")} expression
 * — survive future regressions.
 */
class MentorChatControllerAuthIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private StubMentorChatStarter mentorChatStarter;

    @BeforeEach
    void resetMentorChatStarter() {
        mentorChatStarter.reset();
    }

    @Autowired
    private WorkspaceRepository workspaceRepositoryForFeatures;

    /** Flip the per-workspace mentor toggle on so the controller's feature gate passes. */
    private Workspace enableMentor(Workspace workspace) {
        workspace.getFeatures().setMentorEnabled(true);
        return workspaceRepositoryForFeatures.save(workspace);
    }

    /** Minimal valid mentor turn request body. */
    private static Map<String, Object> validBody() {
        return Map.of(
                "id",
                UUID.randomUUID(),
                "message",
                Map.of(
                        "id",
                        UUID.randomUUID(),
                        "role",
                        "user",
                        "parts",
                        List.of(Map.of("type", "text", "text", "hi"))));
    }

    @Test
    @DisplayName("unauthenticated cookie-style POST → 403 (CSRF rejects before the controller runs)")
    void unauthenticated_returnsForbidden() {
        // ADR 0017 enabled stateless double-submit CSRF on the resource-server chain. A cookie-style
        // (non-bearer) state-changing request with no X-XSRF-TOKEN is rejected 403 by the CSRF filter
        // before it reaches the controller (the request is unauthenticated at that point, so the
        // AccessDenied resolves to 403). Detailed 403-vs-401 semantics live in CsrfProtectionIntegrationTest.
        webTestClient
                .post()
                .uri("/workspaces/{workspaceSlug}/mentor/chat", "any-slug")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithMentorUser
    void authenticatedButNotMember_returnsForbidden() {
        persistUser("mentor");
        User owner = persistUser("workspace-owner-for-mentor-test");
        Workspace workspace = createWorkspace("mentor-auth-space", "MentorAuth", "mentor-auth", AccountType.ORG, owner);
        enableMentor(workspace);

        webTestClient
                .post()
                .uri("/workspaces/{workspaceSlug}/mentor/chat", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithMentorUser
    void authenticatedMember_returnsOkSseStreamAndDispatchesService() throws Exception {
        User mentor = persistUser("mentor");
        User owner = persistUser("workspace-owner-for-mentor-happy");
        Workspace workspace = createWorkspace("mentor-happy-space", "Happy", "mentor-happy", AccountType.ORG, owner);
        enableMentor(workspace);
        ensureWorkspaceMembership(workspace, mentor, WorkspaceMembership.WorkspaceRole.MEMBER);

        webTestClient
                .post()
                .uri("/workspaces/{workspaceSlug}/mentor/chat", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(validBody())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectHeader()
                .valueEquals("x-vercel-ai-ui-message-stream", "v1")
                .expectHeader()
                .valueEquals("Cache-Control", "no-cache")
                .expectBody(Void.class);

        assertThat(mentorChatStarter.awaitInvocation()).isTrue();
    }

    @Test
    @WithMentorUser
    void authenticatedMember_mentorDisabled_returnsNotFound() {
        User mentor = persistUser("mentor");
        User owner = persistUser("workspace-owner-for-mentor-disabled");
        Workspace workspace =
                createWorkspace("mentor-disabled-space", "Disabled", "mentor-disabled", AccountType.ORG, owner);
        // Deliberately NOT enabling mentor — the gate is what we're asserting.
        ensureWorkspaceMembership(workspace, mentor, WorkspaceMembership.WorkspaceRole.MEMBER);

        webTestClient
                .post()
                .uri("/workspaces/{workspaceSlug}/mentor/chat", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(validBody())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }
}
