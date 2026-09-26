package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import static de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures.admittedMentorConfig;

import de.tum.cit.aet.hephaestus.agent.mentor.chat.exception.MentorStreamLostException;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.TranslatorState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The read side of an interrupted reply: what the thread endpoint returns once a turn has recorded one.
 * How a stream loss reaches that record is covered by {@code MentorChatServiceTest}.
 */
class MentorInterruptedReplyIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private MentorTurnPersistence persistence;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @WithMentorUser
    void shouldReturnAnInterruptedReplyAsInterruptedWithoutItsServerSideCause() {
        User mentor = persistUser("mentor");
        Workspace workspace =
                createWorkspace("interrupted-reply", "Interrupted Reply", "org-ir", AccountType.ORG, mentor);
        ensureWorkspaceMembership(workspace, mentor, WorkspaceRole.MEMBER);
        UUID threadId = UUID.randomUUID();
        ChatThread thread = persistence.ensureThread(
                workspace.getId(), threadId, mentor, Set.of(mentor.getId()), "How do I link issues?");
        UUID assistantId = UUID.randomUUID();
        var cookie =
                persistence.persistInFlight(thread, "How do I link issues?", assistantId, null, admittedMentorConfig());

        TranslatorState state = new TranslatorState(assistantId);
        state.openTextBlock("text-0");
        state.appendText("Link the issue so");
        state.closeTextBlock();
        persistence.interrupt(cookie, state, new ExecutionException(new MentorStreamLostException()));

        String reply = "$.messages[?(@.id == '%s')]".formatted(assistantId);
        webTestClient
                .get()
                .uri("/workspaces/{slug}/mentor/threads/{threadId}", workspace.getWorkspaceSlug(), threadId)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath(reply + ".metadata.status")
                .isEqualTo("interrupted")
                .jsonPath(reply + ".parts[?(@.type == 'text')].text")
                .isEqualTo("Link the issue so")
                .jsonPath(reply + ".metadata.error")
                .doesNotExist();
    }
}
