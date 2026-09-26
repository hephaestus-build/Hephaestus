package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.StubMentorChatStarter;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Who may use Heph on the web, through the real filter chain: a member of a workspace that has Heph
 * on, decided per request from workspace state. No account-wide grant is involved, so none of these
 * tokens carries an authority beyond the instance administrator's own.
 */
class MentorAccessIntegrationTest extends AbstractWorkspaceIntegrationTest {

    /** Signs in as the user with login {@code mentor}; carries no authority. */
    private static final String MEMBER = "mock-jwt-token-for-mentor-user";
    /** Signs in as the user with login {@code admin}, the owner {@link #ensureOwnerMembership} creates. */
    private static final String OWNER = "mock-jwt-token-for-admin-user";

    @Autowired
    private WebTestClient client;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private StubMentorChatStarter mentorChatStarter;

    private User member;

    @BeforeEach
    void setUp() {
        mentorChatStarter.reset();
        member = persistUser("mentor");
    }

    private Workspace workspace(String slug, boolean mentorEnabled) {
        Workspace workspace = createWorkspace(slug, slug, slug, AccountType.ORG, persistUser("owner-" + slug));
        ensureOwnerMembership(workspace);
        workspace.getFeatures().setMentorEnabled(mentorEnabled);
        return workspaces.save(workspace);
    }

    private WebTestClient.ResponseSpec chat(String bearer, Workspace workspace) {
        return client.post()
                .uri("/workspaces/{slug}/mentor/chat", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(bearer))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "id",
                        UUID.randomUUID(),
                        "message",
                        Map.of(
                                "id",
                                UUID.randomUUID(),
                                "role",
                                "user",
                                "parts",
                                List.of(Map.of("type", "text", "text", "hi")))))
                .exchange();
    }

    private WebTestClient.ResponseSpec threads(String bearer, Workspace workspace) {
        return client.get()
                .uri("/workspaces/{slug}/mentor/threads", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(bearer))
                .exchange();
    }

    private void setMentorEnabled(Workspace workspace, boolean enabled) {
        client.patch()
                .uri("/workspaces/{slug}/features", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(OWNER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mentorEnabled", enabled))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.mentorEnabled")
                .isEqualTo(enabled);
    }

    @Test
    void shouldFollowTheWorkspaceSwitchWithoutANewSignInWhenAnAdminTurnsHephOnAndOff() throws Exception {
        Workspace workspace = workspace("mentor-access-switch", false);
        ensureWorkspaceMembership(workspace, member, WorkspaceRole.MEMBER);

        chat(MEMBER, workspace).expectStatus().isNotFound().expectBody(Void.class);

        setMentorEnabled(workspace, true);
        chat(MEMBER, workspace).expectStatus().isOk().expectBody(Void.class);
        assertThat(mentorChatStarter.awaitInvocation()).isTrue();

        setMentorEnabled(workspace, false);
        chat(MEMBER, workspace).expectStatus().isNotFound().expectBody(Void.class);
        // Turning Heph off stops new turns; the member's saved conversations stay readable.
        threads(MEMBER, workspace).expectStatus().isOk().expectBody(Void.class);
    }

    @Test
    void shouldDecideEachWorkspaceOnItsOwnWhenOneSignInSwitchesBetweenWorkspaces() {
        Workspace on = workspace("mentor-access-on", true);
        Workspace off = workspace("mentor-access-off", false);
        Workspace foreign = workspace("mentor-access-foreign", true);
        ensureWorkspaceMembership(on, member, WorkspaceRole.MEMBER);
        ensureWorkspaceMembership(off, member, WorkspaceRole.MEMBER);

        chat(MEMBER, on).expectStatus().isOk().expectBody(Void.class);
        chat(MEMBER, off).expectStatus().isNotFound().expectBody(Void.class);
        chat(MEMBER, foreign).expectStatus().isForbidden().expectBody(Void.class);
        threads(MEMBER, foreign).expectStatus().isForbidden().expectBody(Void.class);
    }

    @Test
    void shouldRefuseHephWhenAWorkspaceIsPubliclyViewableAndTheReaderIsNoMember() {
        Workspace workspace = workspace("mentor-access-public", true);
        workspace.setIsPubliclyViewable(true);
        workspaces.save(workspace);

        // The workspace filter admits a public read; only membership stops the signed-in reader.
        threads(MEMBER, workspace).expectStatus().isForbidden().expectBody(Void.class);
        chat(MEMBER, workspace).expectStatus().isForbidden().expectBody(Void.class);
        client.get()
                .uri("/workspaces/{slug}/mentor/threads", workspace.getWorkspaceSlug())
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    void shouldRefuseHephWhenAnInstanceAdminReachesTheWorkspaceOnlyThroughElevation() {
        Workspace workspace = workspace("mentor-access-elevated", true);
        String administrator = "mock-jwt-admin-"
                + persistInstanceAdmin("Elevated administrator").getId();

        // Elevation still administers the workspace...
        client.patch()
                .uri("/workspaces/{slug}/features", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(administrator))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mentorEnabled", true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        // ...but gives no place in it: Heph is for the workspace's own members.
        chat(administrator, workspace).expectStatus().isForbidden().expectBody(Void.class);
        threads(administrator, workspace).expectStatus().isForbidden().expectBody(Void.class);
        client.delete()
                .uri("/workspaces/{slug}/mentor/threads/{threadId}", workspace.getWorkspaceSlug(), UUID.randomUUID())
                .headers(headers -> headers.setBearerAuth(administrator))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void shouldKeepTheConversationListReadableWhenTheMemberChoseNoAi() {
        Workspace workspace = workspace("mentor-access-no-ai", true);
        ensureWorkspaceMembership(workspace, member, WorkspaceRole.MEMBER);
        client.put()
                .uri("/workspaces/{slug}/onboarding/me/ai-choice", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("choice", "NO_AI"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.aiChoice")
                .isEqualTo("NO_AI");

        // No AI is enforced where a model would be chosen (MemberAiRoutingAdapter), not by locking
        // the member out of what they already have.
        threads(MEMBER, workspace).expectStatus().isOk().expectBody(Void.class);
    }
}
