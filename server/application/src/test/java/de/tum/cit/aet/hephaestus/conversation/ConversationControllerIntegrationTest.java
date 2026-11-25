package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignment;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignmentRepository;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationRefusal;
import de.tum.cit.aet.hephaestus.core.TimeOrderedIds;
import de.tum.cit.aet.hephaestus.core.exception.ProblemType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The app's Hephaestus routes, through the real filter chain, as the developer the token signs in. */
class ConversationControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ConversationTurnClaims claims;

    /** Signs in as the user with login {@code conversation}. */
    private static final String MEMBER = "mock-jwt-token-for-mentor-user";

    @Autowired
    private WebTestClient client;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private WorkspaceModelAssignmentRepository bindings;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationEntryService entryService;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationEntryRepository entryRepository;

    @Autowired
    private ConversationTurnRepository turnRepository;

    @Autowired
    private ConversationSituation situation;

    @Autowired
    private ConversationRedaction redaction;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    private User developer;
    private Workspace workspace;
    private WorkspaceModelAssignment binding;

    @BeforeEach
    void setUpWorkspace() {
        developer = persistUser("mentor");
        workspace = createWorkspace("ask-api", "Hephaestus API", "ask-api", AccountType.ORG, persistUser("ask-owner"));
        ensureMembership(workspace, developer, WorkspaceRole.MEMBER);
        var connection = connections.save(LlmCatalogTestFixtures.connection("ask-api"));
        var model = models.save(LlmCatalogTestFixtures.model(connection, "ask-api", "ask-model"));
        binding = bindings.save(LlmCatalogTestFixtures.conversationBinding(workspace, model));
    }

    private WebTestClient.ResponseSpec ask(UUID key, String text, @Nullable UUID parentEntryId) {
        return ask(workspace.getWorkspaceSlug(), MEMBER, key, text, parentEntryId);
    }

    private WebTestClient.ResponseSpec ask(
            String slug, String bearer, UUID key, String text, @Nullable UUID parentEntryId) {
        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        body.put("parentEntryId", parentEntryId);
        body.put("pageContext", "Pull request #421 in acme/web");
        return ask(slug, bearer, key, body);
    }

    private WebTestClient.ResponseSpec ask(String slug, String bearer, UUID key, Map<String, Object> body) {
        return client.post()
                .uri("/workspaces/{slug}/conversation/turns", slug)
                .header("Idempotency-Key", key.toString())
                .headers(headers -> headers.setBearerAuth(bearer))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private WebTestClient.BodyContentSpec get(String path) {
        return client.get()
                .uri("/workspaces/ask-api/conversation" + path)
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    private WebTestClient.ResponseSpec write(String method, String path, @Nullable Object body) {
        WebTestClient.RequestBodySpec request = client.method(HttpMethod.valueOf(method))
                .uri("/workspaces/ask-api/conversation" + path)
                .headers(headers -> headers.setBearerAuth(MEMBER));
        return body == null
                ? request.exchange()
                : request.contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .exchange();
    }

    private Conversation main() {
        return conversations.findOrCreateMain(workspace.getId(), accountId(developer));
    }

    private ConversationTurnService.Submission submit(Conversation conversation, String text) {
        return turns.submit(
                workspace.getId(),
                conversation.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
    }

    @Test
    void shouldAnswerWithAnEmptyConversationWhenTheDeveloperNeverWrote() {
        get("").jsonPath("$.id")
                .doesNotExist()
                .jsonPath("$.latestSeq")
                .isEqualTo(0)
                .jsonPath("$.activeTurns.length()")
                .isEqualTo(0);
        get("/entries").jsonPath("$.content.length()").isEqualTo(0);
    }

    @Test
    void shouldMoveTheAnswerWatermarkOnlyForAShownHephaestusAnswerInTheConversationOrAThread() throws Exception {
        Conversation main = main();
        ConversationEntry first =
                entryService.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("One"));
        assertThat(lastAnswerAt()).isEqualTo(stored(first));

        Thread.sleep(5); // NOPMD - the next answer needs a later timestamp, which needs real elapsed time
        Conversation thread = conversations.openThread(workspace.getId(), first.getId());
        ConversationEntry inThread = entryService.append(
                workspace.getId(), thread.getId(), ConversationLedgerFixtures.hephaestus("In a thread"));
        assertThat(lastAnswerAt()).as("an answer in a thread").isEqualTo(stored(inThread));

        Thread.sleep(5); // NOPMD - the next answer needs a later timestamp, which needs real elapsed time
        submit(main, "A question is not an answer.");
        entryService.append(
                workspace.getId(),
                main.getId(),
                ConversationLedgerFixtures.digest(UUID.randomUUID().toString()));
        assertThat(lastAnswerAt()).as("a question or a digest").isEqualTo(stored(inThread));

        redaction.redact(List.of(inThread.getId()));
        assertThat(lastAnswerAt()).as("an erased answer is not shown").isEqualTo(stored(first));
    }

    private @Nullable Instant lastAnswerAt() {
        byte[] body = get("").returnResult().getResponseBody();
        JsonNode value = JsonMapper.shared().readTree(body).path("lastAnswerAt");
        return value.isString() ? Instant.parse(value.asString()) : null;
    }

    /** The entry's time as PostgreSQL keeps it, rounded to microseconds. */
    private Instant stored(ConversationEntry entry) {
        return entryRepository.findById(entry.getId()).orElseThrow().getCreatedAt();
    }

    @Test
    void shouldKeepTheLatestReadTimeForEveryDeviceAndLetNoOtherMemberMoveIt() {
        Conversation main = main();
        entryService.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Answer"));
        Instant read = Instant.parse("2026-10-12T14:41:00Z");

        write("PUT", "/read", Map.of("at", read.toString()))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.lastReadAt")
                .isEqualTo(read.toString());
        assertThat(lastReadAt()).isEqualTo(read);

        write("PUT", "/read", Map.of("at", read.minusSeconds(3600).toString()))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.lastReadAt")
                .isEqualTo(read.toString());
        assertThat(lastReadAt()).as("an earlier time from another device").isEqualTo(read);

        // Another member marks only their own conversation; the request names none.
        ensureMembership(workspace, persistUser("testuser"), WorkspaceRole.MEMBER);
        client.put()
                .uri("/workspaces/ask-api/conversation/read")
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

        write("PUT", "/read", Map.of("at", "2999-01-01T00:00:00Z"))
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        assertThat(lastReadAt()).as("never past now").isBefore(Instant.now().plusSeconds(60));
    }

    private @Nullable Instant lastReadAt() {
        byte[] body = get("").returnResult().getResponseBody();
        JsonNode value = JsonMapper.shared().readTree(body).path("lastReadAt");
        return value.isString() ? Instant.parse(value.asString()) : null;
    }

    @Test
    void shouldStoreTheQuestionOnceWithItsPageContextWhenTheSameRequestIsSentTwice() {
        UUID requestId = TimeOrderedIds.next();

        var created = ask(requestId, "How did my last review go?", null)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.turn.status")
                .isEqualTo("QUEUED")
                .jsonPath("$.turn.attempt")
                .isEqualTo(0)
                .jsonPath("$.turn.requestId")
                .doesNotExist()
                .jsonPath("$.question.kind")
                .isEqualTo("USER")
                .jsonPath("$.question.surface")
                .isEqualTo("APP")
                .jsonPath("$.question.text")
                .isEqualTo("How did my last review go?")
                .jsonPath("$.question.requestId")
                .doesNotExist()
                .returnResult();
        JsonNode response = JsonMapper.shared().readTree(Objects.requireNonNull(created.getResponseBody()));
        String turnId = response.path("turn").path("id").asString();
        String entryId = response.path("question").path("id").asString();
        ask(
                        workspace.getWorkspaceSlug(),
                        MEMBER,
                        requestId,
                        Map.of(
                                "requestId", requestId.toString(),
                                "text", "How did my last review go?",
                                "pageContext", "Pull request #421 in acme/web"))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.turn.id")
                .isEqualTo(turnId)
                .jsonPath("$.question.id")
                .isEqualTo(entryId);
        ask(requestId, "A different message", null)
                .expectStatus()
                .isEqualTo(422)
                .expectBody(Void.class);

        ConversationTurn turn = turnRepository.findById(UUID.fromString(turnId)).orElseThrow();
        assertThat(turn.getPageContext()).isEqualTo("Pull request #421 in acme/web");
        assertThat(situation.render(workspace.getId(), turn.getId(), ZoneOffset.UTC))
                .contains("They asked from this page in Hephaestus: Pull request #421 in acme/web.");
        assertThat(turnRepository.findByWorkspaceIdAndConversationIdAndStatus(
                        workspace.getId(), turn.getConversationId(), ConversationTurn.Status.QUEUED))
                .extracting(ConversationTurn::getId)
                .containsExactly(turn.getId());
        get("/entries")
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].turnId")
                .isEqualTo(turn.getId().toString());
    }

    @Test
    void shouldRefuseAnInFlightCreateAndAcceptTheKeyAfterTheTransactionEnds() {
        var key = TimeOrderedIds.next();
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lock, 0))")
                    .param("lock", "client-input:" + workspace.getId() + ":" + accountId(developer) + ":" + key)
                    .query((row, index) -> true)
                    .single();
            ask(key, "A message", null)
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.type")
                    .isEqualTo(ProblemType.IDEMPOTENCY_KEY_IN_FLIGHT.uri().toString())
                    .jsonPath("$.label.code")
                    .isEqualTo("problem.idempotency-key-in-flight");
        });
        ask(key, "A message", null).expectStatus().isOk().expectBody(Void.class);
        get("/entries").jsonPath("$.content.length()").isEqualTo(1);
    }

    @Test
    void shouldReplayOnlyTheCurrentQuestionAfterForgetAndRefuseAfterMembershipLoss() {
        var key = TimeOrderedIds.next();
        var created = ask(key, "Remove these words", null)
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult();
        var message = JsonMapper.shared()
                .readTree(Objects.requireNonNull(created.getResponseBody()))
                .path("question");
        String id = message.path("id").asString();
        write("DELETE", "/entries/" + id, null).expectStatus().isNoContent().expectBody(Void.class);
        ask(key, "Remove these words", null)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.question.id")
                .isEqualTo(id)
                .jsonPath("$.question.deleted")
                .isEqualTo(true)
                .jsonPath("$.question.text")
                .isEqualTo("");
        get("/entries").jsonPath("$.content.length()").isEqualTo(1);

        jdbc.sql("UPDATE membership SET left_at=now() WHERE workspace_id=:workspace AND account_id=:account")
                .param("workspace", workspace.getId())
                .param("account", accountId(developer))
                .update();
        ask(key, "Remove these words", null)
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.question")
                .doesNotExist();
        assertThat(jdbc.sql("SELECT count(*) FROM conversation_entry WHERE workspace_id=:workspace AND kind='USER'")
                        .param("workspace", workspace.getId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void shouldRefuseAnExpiredKeyWithoutStoringAQuestion() {
        var key = TimeOrderedIds.next();
        long expired = Instant.now().minus(Duration.ofHours(73)).toEpochMilli();
        var expiredKey =
                new UUID((expired << 16) | (key.getMostSignificantBits() & 0xffff), key.getLeastSignificantBits());
        ask(expiredKey, "An expired submission", null)
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        get("/entries").jsonPath("$.content.length()").isEqualTo(0);
    }

    @Test
    void shouldScopeAKeyToItsActorAndWorkspace() {
        var key = TimeOrderedIds.next();
        var other = persistUser("testuser");
        ensureMembership(workspace, other, WorkspaceRole.MEMBER);
        var otherSpace = createWorkspace(
                "client-input-other",
                "Other workspace",
                "client-input-other",
                AccountType.ORG,
                persistUser("other-owner"));
        ensureMembership(otherSpace, developer, WorkspaceRole.MEMBER);
        bindings.save(LlmCatalogTestFixtures.conversationBinding(
                otherSpace, Objects.requireNonNull(binding.getInstanceModel())));
        ask(key, "First message", null).expectStatus().isOk().expectBody(Void.class);
        ask(workspace.getWorkspaceSlug(), "mock-jwt-token-for-test-user", key, "Second message", null)
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        ask(otherSpace.getWorkspaceSlug(), MEMBER, key, "Third message", null)
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        assertThat(JdbcClient.create(PostgreSQLTestContainer.ownerJdbc())
                        .sql("SELECT workspace_id || ':' || account_id FROM client_input WHERE idem_key=:key")
                        .param("key", key)
                        .query(String.class)
                        .list())
                .as("one receipt per actor and workspace, each owned by its own pair")
                .containsExactlyInAnyOrder(
                        workspace.getId() + ":" + accountId(developer),
                        workspace.getId() + ":" + accountId(other),
                        otherSpace.getId() + ":" + accountId(developer));
    }

    /** A turn as the API sends it, so a test can tell an absent property from a null one. */
    private static final ParameterizedTypeReference<Map<String, Object>> TURN_JSON =
            new ParameterizedTypeReference<>() {};

    @Test
    void shouldSayInEveryReadOfATurnWhetherItWaitsForAFreeHephaestus() {
        // The suite's deployment has one Hephaestus slot (application-test.yml), and the started turn takes it.
        ConversationTurnService.Submission first = submit(main(), "What did the review say?");
        assertThat(claims.claimNext()).isPresent();
        UUID parent = first.question().getId();
        UUID waits = TimeOrderedIds.next();
        UUID followsUp = TimeOrderedIds.next();

        var waitingResponse = ask(waits, "And this one?", parent)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.turn.waitingFor")
                .isEqualTo("CAPACITY")
                .returnResult();
        var followUpResponse = ask(followsUp, "And after that?", parent)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.turn.status")
                .isEqualTo("QUEUED")
                .jsonPath("$.turn")
                .value(
                        TURN_JSON,
                        turn -> assertThat(turn)
                                .as("it waits for the question before it, and zod refuses a null")
                                .doesNotContainKey("waitingFor"))
                .returnResult();
        String waiting = JsonMapper.shared()
                .readTree(Objects.requireNonNull(waitingResponse.getResponseBody()))
                .at("/turn/id")
                .asString();
        String followUp = JsonMapper.shared()
                .readTree(Objects.requireNonNull(followUpResponse.getResponseBody()))
                .at("/turn/id")
                .asString();

        get("").jsonPath("$.activeTurns[?(@.waitingFor == 'CAPACITY')].id")
                .isEqualTo(waiting)
                .jsonPath("$.activeTurns[?(@.id == '" + followUp + "')]")
                .value(
                        new ParameterizedTypeReference<List<Map<String, Object>>>() {},
                        found -> assertThat(found)
                                .singleElement()
                                .satisfies(turn -> assertThat(turn).doesNotContainKey("waitingFor")));
        write("PATCH", "/turns/" + followUp + "/status", Map.of("status", "STOPPED"))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$")
                .value(
                        TURN_JSON,
                        turn -> assertThat(turn)
                                .containsEntry("status", "STOPPED")
                                .doesNotContainKey("waitingFor"));
    }

    @Test
    void shouldSayWhileATurnRunsThatItStartsAndHowManyAttemptsItMayTake() {
        submit(main(), "What did the review say?");
        ConversationTurn running = claims.claimNext().orElseThrow();

        get("").jsonPath("$.activeTurns[0].phase")
                .isEqualTo("STARTING")
                .jsonPath("$.activeTurns[0].maxAttempts")
                .isEqualTo(ConversationTurn.MAX_ATTEMPTS);
        write("PATCH", "/turns/" + running.getId() + "/status", Map.of("status", "STOPPED"))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$")
                .value(
                        TURN_JSON,
                        turn -> assertThat(turn)
                                .containsEntry("status", "STOPPED")
                                .doesNotContainKey("phase")
                                .doesNotContainKey("failureReason"));
    }

    @Test
    void shouldOpenAThreadAndCountItsRepliesWhenTheDeveloperRepliesToAMessage() {
        ConversationTurnService.Submission question = submit(main(), "What is a good pull request description?");
        UUID parent = question.question().getId();

        ask(TimeOrderedIds.next(), "And for a small fix?", parent)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.turn.parentEntryId")
                .isEqualTo(parent.toString())
                .jsonPath("$.question.parentEntryId")
                .isEqualTo(parent.toString());

        Conversation thread = conversationRepository
                .findByWorkspaceIdAndParentEntryId(workspace.getId(), parent)
                .orElseThrow();
        get("/entries")
                .jsonPath("$.content[0].id")
                .isEqualTo(parent.toString())
                .jsonPath("$.content[0].thread.conversationId")
                .isEqualTo(thread.getId().toString())
                .jsonPath("$.content[0].thread.replyCount")
                .isEqualTo(1);
        get("/entries/" + parent + "/thread")
                .jsonPath("$.parent.id")
                .isEqualTo(parent.toString())
                .jsonPath("$.conversationId")
                .isEqualTo(thread.getId().toString())
                .jsonPath("$.replies.content[0].text")
                .isEqualTo("And for a small fix?");
        get("").jsonPath("$.activeTurns.length()")
                .isEqualTo(2)
                .jsonPath("$.threads[0].parentEntryId")
                .isEqualTo(parent.toString());
    }

    @Test
    void shouldPageUpwardWhenOlderMessagesExist() {
        Conversation main = main();
        ConversationEntry first =
                entryService.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("One"));
        ConversationEntry second =
                entryService.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Two"));
        ConversationEntry third =
                entryService.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Three"));

        get("/entries?size=2")
                .jsonPath("$.content[*].id")
                .isEqualTo(List.of(second.getId().toString(), third.getId().toString()))
                .jsonPath("$.nextCursor")
                .isEqualTo(String.valueOf(second.getSeq()));
        get("/entries?size=2&cursor=" + second.getSeq())
                .jsonPath("$.content[*].id")
                .isEqualTo(List.of(first.getId().toString()))
                .jsonPath("$.nextCursor")
                .doesNotExist();
    }

    @Test
    void shouldRefuseACursorThatIsNotAPosition() {
        client.get()
                .uri("/workspaces/ask-api/conversation/entries?cursor=not-a-position")
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void shouldShowHephaestusMessagesWithTheFeedbackTheyShowedWhenToolResultsWereStored() {
        ConversationTurnService.Submission question = submit(main(), "What did the review say?");
        ConversationTurn running = claims.claimNext().orElseThrow();
        UUID shown = UUID.randomUUID();
        UUID refused = UUID.randomUUID();
        ObjectNode shownCall = JsonNodeFactory.instance
                .objectNode()
                .put("feedbackId", shown.toString())
                .put("text", "Name why.");
        ObjectNode refusedCall = JsonNodeFactory.instance
                .objectNode()
                .put("feedbackId", refused.toString())
                .put("text", "Not admitted.");
        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnService.Ending(
                        ConversationTurn.Status.DONE,
                        List.of(
                                ConversationLedgerFixtures.toolCall(
                                        ConversationLedgerFixtures.piId(), "call-shown", "show_feedback", shownCall),
                                ConversationLedgerFixtures.toolResult(
                                        ConversationLedgerFixtures.piId(),
                                        "call-shown",
                                        "show_feedback",
                                        "Shown",
                                        false),
                                ConversationLedgerFixtures.toolCall(
                                        ConversationLedgerFixtures.piId(),
                                        "call-refused",
                                        "show_feedback",
                                        refusedCall),
                                ConversationLedgerFixtures.toolResult(
                                        ConversationLedgerFixtures.piId(), "call-refused", "show_feedback", "No", true),
                                ConversationLedgerFixtures.toolCall(
                                        ConversationLedgerFixtures.piId(),
                                        "call-fetch",
                                        "fetch_context",
                                        JsonNodeFactory.instance.objectNode().put("path", "results.json")),
                                ConversationLedgerFixtures.toolResult(
                                        ConversationLedgerFixtures.piId(), "call-fetch", "fetch_context", "{}", false),
                                ConversationLedgerFixtures.answer(
                                        ConversationLedgerFixtures.piId(), "That is all I saw.")),
                        null,
                        null));

        get("/entries")
                .jsonPath("$.content[*].kind")
                .isEqualTo(List.of("USER", "ANSWER", "ANSWER"))
                .jsonPath("$.content[0].id")
                .isEqualTo(question.question().getId().toString())
                .jsonPath("$.content[1].text")
                .isEqualTo("")
                .jsonPath("$.content[1].links.length()")
                .isEqualTo(1)
                .jsonPath("$.content[1].links[0].kind")
                .isEqualTo("FEEDBACK")
                .jsonPath("$.content[1].links[0].id")
                .isEqualTo(shown.toString())
                .jsonPath("$.content[1].links[0].text")
                .isEqualTo("Name why.")
                // Feedback the developer cannot read names no practice or item to link to.
                .jsonPath("$.content[1].links[0].practiceSlug")
                .doesNotExist()
                .jsonPath("$.content[2].text")
                .isEqualTo("That is all I saw.");
    }

    @Test
    void shouldNotShowARefusedCallWhenAnotherTurnOnThePageUsedTheSameCallIdSuccessfully() {
        endWithFeedbackCall(started(submit(main(), "First question")), "Shown in the first turn.", false);
        endWithFeedbackCall(started(submit(main(), "Second question")), "Refused in the second turn.", true);

        get("/entries")
                // The second answer shows nothing: its only call was refused, so the app does not list it.
                .jsonPath("$.content[*].kind")
                .isEqualTo(List.of("USER", "ANSWER", "USER"))
                .jsonPath("$.content[1].links[0].text")
                .isEqualTo("Shown in the first turn.");
    }

    /** Claims turns until the submitted one starts; a turn of another test that starts first is left running. */
    private ConversationTurn started(ConversationTurnService.Submission submitted) {
        while (true) {
            ConversationTurn claimed = claims.claimNext().orElseThrow();
            if (claimed.getId().equals(submitted.turn().getId())) {
                return claimed;
            }
        }
    }

    /** Ends the turn with one {@code show_feedback} call named {@code call-1}, whose result is an error or not. */
    private void endWithFeedbackCall(ConversationTurn running, String text, boolean refused) {
        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnService.Ending(
                        ConversationTurn.Status.DONE,
                        List.of(
                                ConversationLedgerFixtures.toolCall(
                                        ConversationLedgerFixtures.piId(),
                                        "call-1",
                                        "show_feedback",
                                        JsonNodeFactory.instance
                                                .objectNode()
                                                .put(
                                                        "feedbackId",
                                                        UUID.randomUUID().toString())
                                                .put("text", text)),
                                ConversationLedgerFixtures.toolResult(
                                        ConversationLedgerFixtures.piId(),
                                        "call-1",
                                        "show_feedback",
                                        "Result",
                                        refused)),
                        null,
                        null));
    }

    @Test
    void shouldStopTheTurnWhenTheDeveloperStopsIt() {
        ConversationTurnService.Submission question = submit(main(), "Explain my review.");
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

        assertThat(turnRepository.findById(turnId).orElseThrow().getStatus())
                .isEqualTo(ConversationTurn.Status.STOPPED);
        get("/entries")
                .jsonPath("$.content[0].id")
                .isEqualTo(question.question().getId().toString())
                .jsonPath("$.content[0].turnStatus")
                .isEqualTo("STOPPED")
                .jsonPath("$.content[0].turnError")
                .doesNotExist();
    }

    @Test
    void shouldCarryTheOutcomeOfItsTurnOnTheQuestionWhenTheTurnFailed() {
        ConversationTurnService.Submission question = submit(main(), "Explain my review.");
        ConversationTurn running = claims.claimNext().orElseThrow();
        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnService.Ending(
                        ConversationTurn.Status.FAILED,
                        List.of(),
                        null,
                        "Hephaestus could not finish this reply. Try again."));

        get("/entries")
                .jsonPath("$.content[0].id")
                .isEqualTo(question.question().getId().toString())
                .jsonPath("$.content[0].turnStatus")
                .isEqualTo("FAILED")
                .jsonPath("$.content[0].turnError")
                .isEqualTo("Hephaestus could not finish this reply. Try again.");
    }

    @Test
    void shouldSetAndClearTheVoteWhenTheDeveloperRatesHephaestusMessage() {
        Conversation main = main();
        ConversationEntry answer =
                entryService.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Hi"));
        ConversationTurnService.Submission question = submit(main, "Hello?");

        write("PUT", "/entries/" + answer.getId() + "/vote", Map.of("vote", "HELPFUL"))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.vote")
                .isEqualTo("HELPFUL");
        assertThat(entryRepository.findById(answer.getId()).orElseThrow().getVote())
                .isEqualTo(ConversationEntry.Vote.HELPFUL);

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
                .isEqualTo(ProblemType.CONVERSATION_REJECTED.uri().toString())
                .jsonPath("$.detail")
                .isEqualTo(ConversationRejectedException.Reason.NOT_VOTABLE.userMessage());
    }

    @Test
    void shouldEraseTheMessageAndStopItsTurnWhenTheDeveloperDeletesTheirQuestion() {
        Conversation main = main();
        ConversationEntry answer =
                entryService.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Hi"));
        ConversationTurnService.Submission question = submit(main, "Never mind this.");

        write("DELETE", "/entries/" + question.question().getId(), null)
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        ConversationEntry deleted =
                entryRepository.findById(question.question().getId()).orElseThrow();
        assertThat(deleted.isRedacted()).isTrue();
        assertThat(deleted.getText()).isEmpty();
        assertThat(turnRepository
                        .findById(question.turn().getId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(ConversationTurn.Status.STOPPED);
        get("/entries")
                .jsonPath("$.content[1].deleted")
                .isEqualTo(true)
                .jsonPath("$.content[1].text")
                .isEqualTo("");
        write("DELETE", "/entries/" + answer.getId(), null)
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo(ConversationRejectedException.Reason.NOT_DELETABLE.userMessage())
                .jsonPath("$.type")
                .isEqualTo(ProblemType.CONVERSATION_REJECTED.uri().toString())
                .jsonPath("$.label.code")
                .isEqualTo("conversation.rejected.not-deletable");
    }

    @Test
    void shouldRefuseTheQuestionAndStoreNothingWhenHephaestusIsOffInTheWorkspace() {
        binding.setEnabled(false);
        bindings.save(binding);
        UUID requestId = TimeOrderedIds.next();

        ask(requestId, "Are you there?", null)
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(ProblemType.CONVERSATION_REFUSED.uri().toString())
                .jsonPath("$.detail")
                .isEqualTo(ConversationRefusal.UNAVAILABLE.userMessage())
                .jsonPath("$.label.code")
                .isEqualTo("conversation.refused.unavailable")
                .jsonPath("$.label.text")
                .isEqualTo(ConversationRefusal.UNAVAILABLE.userMessage());

        assertThat(JdbcClient.create(PostgreSQLTestContainer.ownerJdbc())
                        .sql("SELECT count(*) FROM client_input WHERE workspace_id=:workspace AND idem_key=:key")
                        .param("workspace", workspace.getId())
                        .param("key", requestId)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(conversationRepository.findMain(workspace.getId(), accountId(developer)))
                .isEmpty();
    }

    @Test
    void shouldNotFindAnotherMembersMessagesWhenTheDeveloperAddressesThem() {
        User colleague = persistUser("ask-colleague");
        ensureMembership(workspace, colleague, WorkspaceRole.MEMBER);
        Conversation theirs = conversations.findOrCreateMain(workspace.getId(), accountId(colleague));
        ConversationEntry answer =
                entryService.append(workspace.getId(), theirs.getId(), ConversationLedgerFixtures.hephaestus("Hi"));
        ConversationTurnService.Submission question = submit(theirs, "Private question");

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
                .uri("/workspaces/ask-api/conversation/entries/" + answer.getId() + "/thread")
                .headers(headers -> headers.setBearerAuth(MEMBER))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        assertThat(parentEntryProblem(answer.getId()))
                .usingRecursiveComparison()
                .ignoringFields("instance", "properties.requestId")
                .isEqualTo(parentEntryProblem(UUID.randomUUID()));

        ConversationEntry untouched = entryRepository.findById(answer.getId()).orElseThrow();
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
                .isEqualTo(ConversationTurn.Status.QUEUED);
    }

    private ProblemDetail parentEntryProblem(UUID entryId) {
        ProblemDetail problem = Objects.requireNonNull(ask(TimeOrderedIds.next(), "Reply in a thread", entryId)
                .expectStatus()
                .isNotFound()
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody());
        problem.setDetail(Objects.requireNonNull(problem.getDetail()).replace(entryId.toString(), "{id}"));
        return problem;
    }
}
