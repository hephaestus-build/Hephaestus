package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The app's Heph routes, through the real filter chain, as the developer the token signs in. */
class MentorConversationControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    /** Signs in as the user with login {@code mentor}. */
    private static final String MEMBER = "mock-jwt-token-for-mentor-user";

    private static final String WORKER = "worker-heph-api";

    @Autowired
    private WebTestClient client;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private WorkspaceAgentBindingRepository bindings;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorEntryService entryService;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorConversationRepository conversationRepository;

    @Autowired
    private MentorEntryRepository entryRepository;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private MentorSituation situation;

    @Autowired
    private MentorRedaction redaction;

    private User developer;
    private Workspace workspace;
    private WorkspaceAgentBinding binding;

    @BeforeEach
    void setUpWorkspace() {
        developer = persistUser("mentor");
        workspace = createWorkspace("heph-api", "Heph API", "heph-api", AccountType.ORG, persistUser("heph-owner"));
        ensureWorkspaceMembership(workspace, developer, WorkspaceRole.MEMBER);
        var connection = connections.save(LlmCatalogTestFixtures.connection("heph-api"));
        var model = models.save(LlmCatalogTestFixtures.model(connection, "heph-api", "heph-model"));
        binding = bindings.save(LlmCatalogTestFixtures.mentorBinding(workspace, model));
    }

    private WebTestClient.ResponseSpec ask(UUID requestId, String text, @Nullable UUID parentEntryId) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("text", text);
        body.put("parentEntryId", parentEntryId);
        body.put("pageContext", "Pull request #421 in acme/web");
        return client.post()
                .uri("/workspaces/heph-api/mentor/turns")
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private WebTestClient.BodyContentSpec get(String path) {
        return client.get()
                .uri("/workspaces/heph-api/mentor" + path)
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    private WebTestClient.ResponseSpec write(String method, String path, @Nullable Object body) {
        WebTestClient.RequestBodySpec request = client.method(HttpMethod.valueOf(method))
                .uri("/workspaces/heph-api/mentor" + path)
                .headers(headers -> headers.setBearerAuth(MEMBER));
        return body == null
                ? request.exchange()
                : request.contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .exchange();
    }

    private MentorConversation main() {
        return conversations.findOrCreateMain(workspace.getId(), developer.getId(), Set.of(developer.getId()));
    }

    private MentorTurnService.Submission submit(MentorConversation conversation, String text) {
        return turns.submit(
                workspace.getId(),
                conversation.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
    }

    @Test
    void shouldAnswerWithAnEmptyConversationWhenTheDeveloperNeverWrote() {
        get("/conversation")
                .jsonPath("$.id")
                .doesNotExist()
                .jsonPath("$.latestSeq")
                .isEqualTo(0)
                .jsonPath("$.activeTurns.length()")
                .isEqualTo(0);
        get("/entries").jsonPath("$.entries.length()").isEqualTo(0);
    }

    @Test
    void shouldMoveTheAnswerWatermarkOnlyForAShownHephAnswerInTheConversationOrAThread() throws Exception {
        MentorConversation main = main();
        MentorEntry first = entryService.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("One"));
        assertThat(lastAnswerAt()).isEqualTo(stored(first));

        Thread.sleep(5);
        MentorConversation thread = conversations.openThread(workspace.getId(), first.getId());
        MentorEntry inThread =
                entryService.append(workspace.getId(), thread.getId(), MentorLedgerFixtures.heph("In a thread"));
        assertThat(lastAnswerAt()).as("an answer in a thread").isEqualTo(stored(inThread));

        Thread.sleep(5);
        submit(main, "A question is not an answer.");
        entryService.append(
                workspace.getId(),
                main.getId(),
                MentorLedgerFixtures.digest(UUID.randomUUID().toString()));
        assertThat(lastAnswerAt()).as("a question or a digest").isEqualTo(stored(inThread));

        redaction.redact(List.of(inThread.getId()));
        assertThat(lastAnswerAt()).as("an erased answer is not shown").isEqualTo(stored(first));
    }

    private @Nullable Instant lastAnswerAt() {
        byte[] body = get("/conversation").returnResult().getResponseBody();
        JsonNode value = JsonMapper.shared().readTree(body).path("lastAnswerAt");
        return value.isString() ? Instant.parse(value.asString()) : null;
    }

    /** The entry's time as PostgreSQL keeps it, rounded to microseconds. */
    private Instant stored(MentorEntry entry) {
        return entryRepository.findById(entry.getId()).orElseThrow().getCreatedAt();
    }

    @Test
    void shouldKeepTheLatestReadTimeForEveryDeviceAndLetNoOtherMemberMoveIt() {
        MentorConversation main = main();
        entryService.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Answer"));
        Instant read = Instant.parse("2026-10-12T14:41:00Z");

        write("PUT", "/conversation/read", Map.of("at", read.toString()))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.lastReadAt")
                .isEqualTo(read.toString());
        assertThat(lastReadAt()).isEqualTo(read);

        write("PUT", "/conversation/read", Map.of("at", read.minusSeconds(3600).toString()))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.lastReadAt")
                .isEqualTo(read.toString());
        assertThat(lastReadAt()).as("an earlier time from another device").isEqualTo(read);

        // Another member marks only their own conversation; the request names none.
        ensureWorkspaceMembership(workspace, persistUser("testuser"), WorkspaceRole.MEMBER);
        client.put()
                .uri("/workspaces/heph-api/mentor/conversation/read")
                .headers(headers -> headers.setBearerAuth("mock-jwt-token-for-test-user"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("at", read.plusSeconds(3600).toString()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.lastReadAt")
                .doesNotExist();
        assertThat(lastReadAt()).isEqualTo(read);

        write("PUT", "/conversation/read", Map.of("at", "2999-01-01T00:00:00Z"))
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        assertThat(lastReadAt()).as("never past now").isBefore(Instant.now().plusSeconds(60));
    }

    private @Nullable Instant lastReadAt() {
        byte[] body = get("/conversation").returnResult().getResponseBody();
        JsonNode value = JsonMapper.shared().readTree(body).path("lastReadAt");
        return value.isString() ? Instant.parse(value.asString()) : null;
    }

    @Test
    void shouldStoreTheQuestionOnceWithItsPageContextWhenTheSameRequestIsSentTwice() {
        UUID requestId = UUID.randomUUID();

        ask(requestId, "How did my last review go?", null)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.turn.status")
                .isEqualTo("QUEUED")
                .jsonPath("$.turn.attempt")
                .isEqualTo(0)
                .jsonPath("$.turn.requestId")
                .isEqualTo(requestId.toString())
                .jsonPath("$.question.kind")
                .isEqualTo("USER")
                .jsonPath("$.question.surface")
                .isEqualTo("APP")
                .jsonPath("$.question.text")
                .isEqualTo("How did my last review go?")
                .jsonPath("$.question.requestId")
                .isEqualTo(requestId.toString());
        ask(requestId, "How did my last review go?", null).expectStatus().isOk().expectBody(Void.class);

        MentorTurn turn = turnRepository
                .findByWorkspaceIdAndRequestId(workspace.getId(), requestId.toString())
                .orElseThrow();
        assertThat(turn.getPageContext()).isEqualTo("Pull request #421 in acme/web");
        assertThat(situation.render(workspace.getId(), turn.getId(), ZoneOffset.UTC))
                .contains("They asked from this page in Hephaestus: Pull request #421 in acme/web.");
        assertThat(turnRepository.findByWorkspaceIdAndConversationIdAndStatus(
                        workspace.getId(), turn.getConversationId(), MentorTurn.Status.QUEUED))
                .extracting(MentorTurn::getId)
                .containsExactly(turn.getId());
        get("/entries")
                .jsonPath("$.entries.length()")
                .isEqualTo(1)
                .jsonPath("$.entries[0].turnId")
                .isEqualTo(turn.getId().toString());
    }

    @Test
    void shouldOpenAThreadAndCountItsRepliesWhenTheDeveloperRepliesToAMessage() {
        MentorTurnService.Submission question = submit(main(), "What is a good pull request description?");
        UUID parent = question.question().getId();

        ask(UUID.randomUUID(), "And for a small fix?", parent)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.turn.parentEntryId")
                .isEqualTo(parent.toString())
                .jsonPath("$.question.parentEntryId")
                .isEqualTo(parent.toString());

        MentorConversation thread = conversationRepository
                .findByWorkspaceIdAndParentEntryId(workspace.getId(), parent)
                .orElseThrow();
        get("/entries")
                .jsonPath("$.entries[0].id")
                .isEqualTo(parent.toString())
                .jsonPath("$.entries[0].thread.conversationId")
                .isEqualTo(thread.getId().toString())
                .jsonPath("$.entries[0].thread.replyCount")
                .isEqualTo(1);
        get("/entries/" + parent + "/thread")
                .jsonPath("$.parent.id")
                .isEqualTo(parent.toString())
                .jsonPath("$.conversationId")
                .isEqualTo(thread.getId().toString())
                .jsonPath("$.entries[0].text")
                .isEqualTo("And for a small fix?");
        get("/conversation")
                .jsonPath("$.activeTurns.length()")
                .isEqualTo(2)
                .jsonPath("$.threads[0].parentEntryId")
                .isEqualTo(parent.toString());
    }

    @Test
    void shouldPageUpwardWhenOlderMessagesExist() {
        MentorConversation main = main();
        MentorEntry first = entryService.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("One"));
        MentorEntry second = entryService.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Two"));
        MentorEntry third = entryService.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Three"));

        get("/entries?limit=2")
                .jsonPath("$.entries[*].id")
                .isEqualTo(List.of(second.getId().toString(), third.getId().toString()))
                .jsonPath("$.earlierBefore")
                .isEqualTo(second.getSeq());
        get("/entries?limit=2&before=" + second.getSeq())
                .jsonPath("$.entries[*].id")
                .isEqualTo(List.of(first.getId().toString()))
                .jsonPath("$.earlierBefore")
                .doesNotExist();
    }

    @Test
    void shouldShowHephsMessagesWithTheFeedbackTheyShowedWhenToolResultsWereStored() {
        MentorTurnService.Submission question = submit(main(), "What did the review say?");
        MentorTurn running = turns.claimNext(WORKER, Duration.ofMinutes(5)).orElseThrow();
        UUID shown = UUID.randomUUID();
        UUID refused = UUID.randomUUID();
        ObjectNode shownCall = JsonNodeFactory.instance
                .objectNode()
                .put("observationId", shown.toString())
                .put("text", "Name why.");
        ObjectNode refusedCall = JsonNodeFactory.instance
                .objectNode()
                .put("observationId", refused.toString())
                .put("text", "Not admitted.");
        turns.end(
                running.getId(),
                running.getAttempt(),
                WORKER,
                MentorTurn.Status.DONE,
                List.of(
                        MentorLedgerFixtures.toolCall(
                                MentorLedgerFixtures.piId(), "call-shown", "link_observation", shownCall),
                        MentorLedgerFixtures.toolResult(
                                MentorLedgerFixtures.piId(), "call-shown", "link_observation", "Shown", false),
                        MentorLedgerFixtures.toolCall(
                                MentorLedgerFixtures.piId(), "call-refused", "link_observation", refusedCall),
                        MentorLedgerFixtures.toolResult(
                                MentorLedgerFixtures.piId(), "call-refused", "link_observation", "No", true),
                        MentorLedgerFixtures.toolCall(
                                MentorLedgerFixtures.piId(),
                                "call-fetch",
                                "fetch_context",
                                JsonNodeFactory.instance.objectNode().put("path", "observations.json")),
                        MentorLedgerFixtures.toolResult(
                                MentorLedgerFixtures.piId(), "call-fetch", "fetch_context", "{}", false),
                        MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "That is all I saw.")),
                null);

        get("/entries")
                .jsonPath("$.entries[*].kind")
                .isEqualTo(List.of("USER", "HEPH", "HEPH"))
                .jsonPath("$.entries[0].id")
                .isEqualTo(question.question().getId().toString())
                .jsonPath("$.entries[1].text")
                .isEqualTo("")
                .jsonPath("$.entries[1].observations.length()")
                .isEqualTo(1)
                .jsonPath("$.entries[1].observations[0].observationId")
                .isEqualTo(shown.toString())
                .jsonPath("$.entries[1].observations[0].text")
                .isEqualTo("Name why.")
                // The observation is not stored here, so there is no practice or work to link to.
                .jsonPath("$.entries[1].observations[0].work")
                .doesNotExist()
                .jsonPath("$.entries[2].text")
                .isEqualTo("That is all I saw.");
    }

    @Test
    void shouldStopTheTurnWhenTheDeveloperStopsIt() {
        MentorTurnService.Submission question = submit(main(), "Explain my review.");
        UUID turnId = question.turn().getId();

        write("PATCH", "/turns/" + turnId + "/status", Map.of("status", "RUNNING"))
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        write("PATCH", "/turns/" + turnId + "/status", Map.of("status", "STOPPED"))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("STOPPED");

        assertThat(turnRepository.findById(turnId).orElseThrow().getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
        get("/entries")
                .jsonPath("$.entries[0].id")
                .isEqualTo(question.question().getId().toString())
                .jsonPath("$.entries[0].turnStatus")
                .isEqualTo("STOPPED")
                .jsonPath("$.entries[0].turnError")
                .doesNotExist();
    }

    @Test
    void shouldCarryTheOutcomeOfItsTurnOnTheQuestionWhenTheTurnFailed() {
        MentorTurnService.Submission question = submit(main(), "Explain my review.");
        MentorTurn running = turns.claimNext(WORKER, Duration.ofMinutes(5)).orElseThrow();
        turns.end(
                running.getId(),
                running.getAttempt(),
                WORKER,
                MentorTurn.Status.FAILED,
                List.of(),
                "Heph could not finish this reply. Try again.");

        get("/entries")
                .jsonPath("$.entries[0].id")
                .isEqualTo(question.question().getId().toString())
                .jsonPath("$.entries[0].turnStatus")
                .isEqualTo("FAILED")
                .jsonPath("$.entries[0].turnError")
                .isEqualTo("Heph could not finish this reply. Try again.");
    }

    @Test
    void shouldSetAndClearTheVoteWhenTheDeveloperRatesHephsMessage() {
        MentorConversation main = main();
        MentorEntry answer = entryService.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Hi"));
        MentorTurnService.Submission question = submit(main, "Hello?");

        write("PUT", "/entries/" + answer.getId() + "/vote", Map.of("vote", "HELPFUL"))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.vote")
                .isEqualTo("HELPFUL");
        assertThat(entryRepository.findById(answer.getId()).orElseThrow().getVote())
                .isEqualTo(MentorEntry.Vote.HELPFUL);

        write("DELETE", "/entries/" + answer.getId() + "/vote", null)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.vote")
                .doesNotExist();
        assertThat(entryRepository.findById(answer.getId()).orElseThrow().getVote())
                .isNull();

        write("PUT", "/entries/" + question.question().getId() + "/vote", Map.of("vote", "HELPFUL"))
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/mentor-rejected")
                .jsonPath("$.reason")
                .isEqualTo("NOT_VOTABLE");
    }

    @Test
    void shouldEraseTheMessageAndStopItsTurnWhenTheDeveloperDeletesTheirQuestion() {
        MentorConversation main = main();
        MentorEntry answer = entryService.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Hi"));
        MentorTurnService.Submission question = submit(main, "Never mind this.");

        write("DELETE", "/entries/" + question.question().getId(), null)
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        MentorEntry deleted =
                entryRepository.findById(question.question().getId()).orElseThrow();
        assertThat(deleted.isRedacted()).isTrue();
        assertThat(deleted.getText()).isEmpty();
        assertThat(turnRepository
                        .findById(question.turn().getId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(MentorTurn.Status.STOPPED);
        get("/entries")
                .jsonPath("$.entries[1].deleted")
                .isEqualTo(true)
                .jsonPath("$.entries[1].text")
                .isEqualTo("");
        write("DELETE", "/entries/" + answer.getId(), null)
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.reason")
                .isEqualTo("NOT_DELETABLE");
    }

    @Test
    void shouldRefuseTheQuestionAndStoreNothingWhenHephIsOffInTheWorkspace() {
        binding.setEnabled(false);
        bindings.save(binding);
        UUID requestId = UUID.randomUUID();

        ask(requestId, "Are you there?", null)
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/mentor-refused")
                .jsonPath("$.reason")
                .isEqualTo("UNAVAILABLE");

        assertThat(turnRepository.findByWorkspaceIdAndRequestId(workspace.getId(), requestId.toString()))
                .isEmpty();
        assertThat(
                        conversationRepository
                                .findMainsByWorkspaceIdAndUserIdIn(workspace.getId(), Set.of(developer.getId()))
                                .stream()
                                .findFirst())
                .isEmpty();
    }

    @Test
    void shouldNotFindAnotherMembersMessagesWhenTheDeveloperAddressesThem() {
        User colleague = persistUser("heph-colleague");
        ensureWorkspaceMembership(workspace, colleague, WorkspaceRole.MEMBER);
        MentorConversation theirs =
                conversations.findOrCreateMain(workspace.getId(), colleague.getId(), Set.of(colleague.getId()));
        MentorEntry answer = entryService.append(workspace.getId(), theirs.getId(), MentorLedgerFixtures.heph("Hi"));
        MentorTurnService.Submission question = submit(theirs, "Private question");

        write("PUT", "/entries/" + answer.getId() + "/vote", Map.of("vote", "HELPFUL"))
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        write("DELETE", "/entries/" + question.question().getId(), null)
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        write("PATCH", "/turns/" + question.turn().getId() + "/status", Map.of("status", "STOPPED"))
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        client.get()
                .uri("/workspaces/heph-api/mentor/entries/" + answer.getId() + "/thread")
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        ask(UUID.randomUUID(), "Reply to them", answer.getId())
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);

        MentorEntry untouched = entryRepository.findById(answer.getId()).orElseThrow();
        assertThat(untouched.getVote()).isNull();
        assertThat(entryRepository
                        .findById(question.question().getId())
                        .orElseThrow()
                        .isRedacted())
                .isFalse();
        assertThat(turnRepository
                        .findById(question.turn().getId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(MentorTurn.Status.QUEUED);
    }
}
