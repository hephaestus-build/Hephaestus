package de.tum.cit.aet.hephaestus.mentor;

import static de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures.admittedMentorConfig;

import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorTurnPersistence;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/** A reopened thread carries its owner's votes on it, and never a vote cast in any other thread. */
class ChatThreadVotesIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private MentorTurnPersistence persistence;

    @Autowired
    private ChatMessageVoteRepository chatMessageVoteRepository;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @WithMentorUser
    void shouldReturnOnlyTheVotesCastInTheRequestedThreadWhenItIsReopened() {
        User mentor = persistUser("mentor");
        User colleague = persistUser("colleague");
        Workspace workspace = createWorkspace("vote-reload", "Vote Reload", "org-vr", AccountType.ORG, mentor);
        ensureWorkspaceMembership(workspace, mentor, WorkspaceRole.MEMBER);
        ensureWorkspaceMembership(workspace, colleague, WorkspaceRole.MEMBER);
        Workspace otherWorkspace =
                createWorkspace("vote-reload-other", "Vote Reload Other", "org-vro", AccountType.ORG, mentor);
        ensureWorkspaceMembership(otherWorkspace, mentor, WorkspaceRole.MEMBER);

        UUID threadId = UUID.randomUUID();
        UUID reply = reply(workspace, threadId, mentor);
        UUID myOtherThreadReply = reply(workspace, UUID.randomUUID(), mentor);
        UUID colleagueThreadId = UUID.randomUUID();
        UUID colleagueReply = reply(workspace, colleagueThreadId, colleague);
        UUID otherWorkspaceThreadId = UUID.randomUUID();
        UUID otherWorkspaceReply = reply(otherWorkspace, otherWorkspaceThreadId, mentor);
        chatMessageVoteRepository.upsert(myOtherThreadReply, true);
        chatMessageVoteRepository.upsert(colleagueReply, true);
        chatMessageVoteRepository.upsert(otherWorkspaceReply, true);

        String vote = "/workspaces/{slug}/mentor/threads/{threadId}/messages/{messageId}/vote";
        webTestClient
                .post()
                .uri(vote, workspace.getWorkspaceSlug(), threadId, reply)
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(Map.of("isUpvoted", false))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        threadIn(workspace, threadId)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.votes")
                .isEqualTo(Map.of(reply.toString(), false));

        webTestClient
                .delete()
                .uri(vote, workspace.getWorkspaceSlug(), threadId, reply)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        threadIn(workspace, threadId)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.votes")
                .isEmpty();

        // The votes ride on the thread read, so its owner and workspace gate is theirs too.
        threadIn(workspace, colleagueThreadId).expectStatus().isNotFound().expectBody(Void.class);
        threadIn(workspace, otherWorkspaceThreadId).expectStatus().isNotFound().expectBody(Void.class);
    }

    private WebTestClient.ResponseSpec threadIn(Workspace workspace, UUID threadId) {
        return webTestClient
                .get()
                .uri("/workspaces/{slug}/mentor/threads/{threadId}", workspace.getWorkspaceSlug(), threadId)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange();
    }

    /** One finished turn in a new thread; returns the assistant reply's id. */
    private UUID reply(Workspace workspace, UUID threadId, User owner) {
        ChatThread thread = persistence.ensureThread(
                workspace.getId(), threadId, owner, Set.of(owner.getId()), "How do I link issues?");
        UUID assistantId = UUID.randomUUID();
        persistence.persistInFlight(thread, "How do I link issues?", assistantId, null, admittedMentorConfig());
        return assistantId;
    }
}
