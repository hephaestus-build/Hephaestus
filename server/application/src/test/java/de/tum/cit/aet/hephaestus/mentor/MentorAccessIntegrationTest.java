package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorRefusal;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Who may use Heph in the app: a member of the workspace, decided per request from workspace state. No
 * account-wide grant is involved, so none of these tokens carries an authority beyond the instance administrator's
 * own.
 */
class MentorAccessIntegrationTest extends AbstractWorkspaceIntegrationTest {

    /** Signs in as the user with login {@code mentor}; carries no authority. */
    private static final String MEMBER = "mock-jwt-token-for-mentor-user";

    @Autowired
    private WebTestClient client;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private WorkspaceAgentBindingRepository bindings;

    @Autowired
    private MentorConversationRepository conversationRepository;

    private User member;

    @BeforeEach
    void setUp() {
        member = persistUser("mentor");
    }

    private Workspace workspace(String slug) {
        Workspace workspace = createWorkspace(slug, slug, slug, AccountType.ORG, persistUser("owner-" + slug));
        ensureOwnerMembership(workspace);
        workspace = workspaces.save(workspace);
        var connection = connections.save(LlmCatalogTestFixtures.connection(slug));
        var model = models.save(LlmCatalogTestFixtures.model(connection, slug, "heph-model"));
        bindings.save(LlmCatalogTestFixtures.mentorBinding(workspace, model));
        return workspace;
    }

    private WebTestClient.ResponseSpec ask(String bearer, Workspace workspace) {
        return client.post()
                .uri("/workspaces/{slug}/mentor/turns", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(bearer))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("requestId", UUID.randomUUID(), "text", "hi"))
                .exchange();
    }

    private WebTestClient.ResponseSpec conversation(String bearer, Workspace workspace) {
        return client.get()
                .uri("/workspaces/{slug}/mentor/conversation", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(bearer))
                .exchange();
    }

    @Test
    void shouldStoreAMembersQuestionWhenTheWorkspaceHasHephOn() {
        Workspace workspace = workspace("mentor-access-member");
        ensureMembership(workspace, member, WorkspaceRole.MEMBER);

        ask(MEMBER, workspace).expectStatus().isOk().expectBody(Void.class);

        assertThat(conversationRepository.findMain(workspace.getId(), accountId(member)))
                .isPresent();
        conversation(MEMBER, workspace).expectStatus().isOk().expectBody(Void.class);
    }

    @Test
    void shouldDecideEachWorkspaceOnItsOwnWhenOneSignInSwitchesBetweenWorkspaces() {
        Workspace own = workspace("mentor-access-own");
        Workspace foreign = workspace("mentor-access-foreign");
        ensureMembership(own, member, WorkspaceRole.MEMBER);

        ask(MEMBER, own).expectStatus().isOk().expectBody(Void.class);
        ask(MEMBER, foreign).expectStatus().isForbidden().expectBody(Void.class);
        conversation(MEMBER, foreign).expectStatus().isForbidden().expectBody(Void.class);
        assertThat(conversationRepository.findMain(foreign.getId(), accountId(member)))
                .isEmpty();
    }

    @Test
    void shouldRefuseHephWhenAWorkspacePublicActivityEnabledAndTheReaderIsNoMember() {
        Workspace workspace = workspace("mentor-access-public");
        workspace.setPublicActivityEnabled(true);
        workspaces.save(workspace);

        // The workspace filter admits a public read; only membership stops the signed-in reader.
        conversation(MEMBER, workspace).expectStatus().isForbidden().expectBody(Void.class);
        ask(MEMBER, workspace).expectStatus().isForbidden().expectBody(Void.class);
        client.get()
                .uri("/workspaces/{slug}/mentor/entries", workspace.getWorkspaceSlug())
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
        ask(administrator, workspace).expectStatus().isForbidden().expectBody(Void.class);
        conversation(administrator, workspace).expectStatus().isForbidden().expectBody(Void.class);
        client.delete()
                .uri("/workspaces/{slug}/mentor/entries/{entryId}", workspace.getWorkspaceSlug(), UUID.randomUUID())
                .headers(headers -> headers.setBearerAuth(administrator))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        client.get()
                .uri("/workspaces/{slug}/mentor/events", workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(administrator))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus()
                .isForbidden()
                .returnResult(Void.class);
    }

    @Test
    void shouldKeepTheConversationReadableAndRefuseNewQuestionsWhenTheMemberChoseNoAi() {
        Workspace workspace = workspace("mentor-access-no-ai");
        ensureMembership(workspace, member, WorkspaceRole.MEMBER);
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

        conversation(MEMBER, workspace).expectStatus().isOk().expectBody(Void.class);
        ask(MEMBER, workspace)
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo(MentorRefusal.NO_AI.userMessage());
        assertThat(conversationRepository.findMain(workspace.getId(), accountId(member)))
                .isEmpty();
    }
}
