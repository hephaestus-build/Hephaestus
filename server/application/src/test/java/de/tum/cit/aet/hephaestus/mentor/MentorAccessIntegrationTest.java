package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorRefusal;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorTurnRunner;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
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
 * Who may use Heph: on the web, through the real filter chain, a member of the workspace, decided per
 * request from workspace state; and a turn only while one of the workspace's Heph bindings is enabled. No
 * account-wide grant is involved, so none of these tokens carries an authority beyond the instance
 * administrator's own. The chat endpoint's turn starter is stubbed, so the binding check is exercised on the
 * turn runner that web and Slack turns share.
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

    @Autowired
    private MentorTurnRunner mentorTurnRunner;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private WorkspaceAgentBindingRepository bindings;

    private User member;

    @BeforeEach
    void setUp() {
        mentorChatStarter.reset();
        member = persistUser("mentor");
    }

    private Workspace workspace(String slug) {
        Workspace workspace = createWorkspace(slug, slug, slug, AccountType.ORG, persistUser("owner-" + slug));
        ensureOwnerMembership(workspace);
        return workspaces.save(workspace);
    }

    /** An enabled Heph binding to an available model, so the workspace starts with Heph on. */
    private WorkspaceAgentBinding hephBinding(Workspace workspace) {
        var connection = connections.save(LlmCatalogTestFixtures.connection(workspace.getWorkspaceSlug()));
        var model = models.save(LlmCatalogTestFixtures.model(connection, workspace.getWorkspaceSlug(), "heph-model"));
        var binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.MENTOR);
        binding.setInstanceModel(model);
        binding.setEnabled(true);
        return bindings.save(binding);
    }

    private void disable(WorkspaceAgentBinding binding) {
        binding.setEnabled(false);
        bindings.save(binding);
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

    @Test
    void shouldHandAMembersChatTurnToTheStarterWhenNoWorkspaceSwitchIsSet() throws Exception {
        Workspace workspace = workspace("mentor-access-member");
        ensureWorkspaceMembership(workspace, member, WorkspaceRole.MEMBER);

        chat(MEMBER, workspace).expectStatus().isOk().expectBody(Void.class);
        assertThat(mentorChatStarter.awaitInvocation()).isTrue();
        threads(MEMBER, workspace).expectStatus().isOk().expectBody(Void.class);
    }

    @Test
    void shouldDecideEachWorkspaceOnItsOwnWhenOneSignInSwitchesBetweenWorkspaces() {
        Workspace own = workspace("mentor-access-own");
        Workspace foreign = workspace("mentor-access-foreign");
        ensureWorkspaceMembership(own, member, WorkspaceRole.MEMBER);

        chat(MEMBER, own).expectStatus().isOk().expectBody(Void.class);
        chat(MEMBER, foreign).expectStatus().isForbidden().expectBody(Void.class);
        threads(MEMBER, foreign).expectStatus().isForbidden().expectBody(Void.class);
    }

    @Test
    void shouldRefuseHephWhenAWorkspaceIsPubliclyViewableAndTheReaderIsNoMember() {
        Workspace workspace = workspace("mentor-access-public");
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
        Workspace workspace = workspace("mentor-access-elevated");
        String administrator = "mock-jwt-admin-"
                + persistInstanceAdmin("Elevated administrator").getId();

        // Elevation still administers the workspace, Heph's bindings included...
        client.get()
                .uri("/workspaces/{slug}/agents", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(administrator))
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
        Workspace workspace = workspace("mentor-access-no-ai");
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

        threads(MEMBER, workspace).expectStatus().isOk().expectBody(Void.class);
    }

    @Test
    void shouldRefuseAHephTurnWhenTheWorkspacesHephBindingIsDisabled() {
        Workspace workspace = workspace("mentor-access-binding-off");
        ensureWorkspaceMembership(workspace, member, WorkspaceRole.MEMBER);
        WorkspaceAgentBinding binding = hephBinding(workspace);
        assertThat(mentorTurnRunner.refusal(workspace.getId(), member.getId())).isEmpty();

        disable(binding);

        assertThat(mentorTurnRunner.refusal(workspace.getId(), member.getId())).contains(MentorRefusal.UNAVAILABLE);
    }
}
