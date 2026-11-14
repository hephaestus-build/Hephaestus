package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.conversation.adapter.ConversationPersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.EntityManager;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@RecordApplicationEvents
class ConversationEntryServiceIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ConversationTurnClaims claims;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private ConversationEntryRepository entryRepository;

    @Autowired
    private ConversationRedaction redaction;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationTurnRepository turnRepository;

    @Autowired
    private SlackWorkspaceContentEraser slackContent;

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** A fixture changes the schema as its owner: the application role cannot. */
    private final JdbcTemplate schemaOwner = PostgreSQLTestContainer.ownerJdbc();

    @Autowired
    private ConversationPersonDataCatalog personDataCatalog;

    private Workspace workspace;
    private Conversation main;
    private long developerAccountId;

    @BeforeEach
    void setUpLedger() {
        User developer = persistUser("ask-entries-developer");
        workspace = createWorkspace("ask-entries", "Hephaestus", "ask-org", AccountType.ORG, developer);
        developerAccountId = accountId(developer);
        main = conversations.findOrCreateMain(workspace.getId(), developerAccountId);
    }

    private ConversationEntry stored(ConversationEntry entry) {
        return entryRepository.findById(entry.getId()).orElseThrow();
    }

    private JsonNode payload(ConversationEntry entry) {
        return Objects.requireNonNull(stored(entry).getPayload());
    }

    @Test
    void shouldRejectARequestIdWhenAnotherConversationAlreadyUsedIt() {
        ConversationEntry answer =
                entries.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Answer"));
        Conversation thread = conversations.openThread(workspace.getId(), answer.getId());
        entries.appendOnce(workspace.getId(), main.getId(), ConversationLedgerFixtures.digest("wd-2"));

        assertThatThrownBy(() -> entries.appendOnce(
                        workspace.getId(), thread.getId(), ConversationLedgerFixtures.digest("wd-2")))
                .isInstanceOfSatisfying(
                        ConversationRejectedException.class,
                        rejected -> assertThat(rejected.reason())
                                .isEqualTo(ConversationRejectedException.Reason.REQUEST_ID_REUSED));
    }

    @Test
    void shouldKeepNoCopyOfTheEarlierWordsWhenTheDeveloperEditsASlackMessageAfterItsTurnRan() {
        ConversationTurnService.Submission submission = slackQuestion("My token is tk-123", "1.5");
        ConversationTurn turn = claim(submission);
        ended(turn, ConversationLedgerFixtures.user(ConversationLedgerFixtures.piId(), "[Slack] My token is tk-123"));

        entries.editSlackMessage(workspace.getId(), main.getId(), "1.5", "My token is gone");

        ConversationEntry edited = stored(submission.question());
        assertThat(edited.getText()).isEqualTo("My token is gone");
        assertThat(payload(edited).toString()).doesNotContain("tk-123").contains("My token is gone");
    }

    @Test
    void shouldKeepNoCopyOfTheEarlierWordsWhenTheHostStoresAQuestionEditedSinceItsContextWasBuilt() {
        ConversationTurnService.Submission submission = slackQuestion("My token is tk-456", "1.6");
        ConversationTurn turn = claim(submission);
        entries.editSlackMessage(workspace.getId(), main.getId(), "1.6", "My token is gone");

        ended(turn, ConversationLedgerFixtures.user(ConversationLedgerFixtures.piId(), "[Slack] My token is tk-456"));

        assertThat(payload(submission.question()).toString())
                .doesNotContain("tk-456")
                .contains("My token is gone");
    }

    @Test
    void shouldEraseEveryCopyOfTheEarlierWordsButKeepTheMessageWhenTheDeveloperEditsASlackMessage() {
        ConversationTurnService.Submission asked = slackQuestion("Deploy on Friday", "2.1");
        ended(claim(asked), ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), "Fine"));
        ConversationEntry kept =
                entries.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Kept"));
        ConversationEntry covering = entries.append(
                workspace.getId(), main.getId(), summary("Friday", ConversationLedgerFixtures.piIdOf(kept)));
        ConversationEntry quote = ended(runningTurn(main), searchResult(asked.question(), "Deploy on Friday"));

        entries.editSlackMessage(workspace.getId(), main.getId(), "2.1", "Deploy on Monday");

        assertThat(stored(covering).isRedacted()).isTrue();
        assertThat(stored(quote).isRedacted()).isTrue();
        assertThat(payload(quote).toString()).doesNotContain("Friday");
        ConversationEntry edited = stored(asked.question());
        assertThat(edited.isRedacted()).isFalse();
        assertThat(edited.getText()).isEqualTo("Deploy on Monday");
        assertThat(stored(kept).isRedacted()).isFalse();
    }

    @Test
    void shouldEraseWhatARunningTurnCopiedOfASlackMessageWhenTheDeveloperEditsItMeanwhile() {
        ConversationTurnService.Submission asked = slackQuestion("Deploy on Friday", "2.2");
        ended(claim(asked), ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), "Fine"));
        ConversationEntry kept =
                entries.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Kept"));
        ConversationTurn running = runningTurn(main);

        entries.editSlackMessage(workspace.getId(), main.getId(), "2.2", "Deploy on Monday");
        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnService.Ending(
                        ConversationTurn.Status.DONE,
                        List.of(compaction("Friday", kept), searchResult(asked.question(), "Deploy on Friday")),
                        null,
                        null));

        assertThat(ConversationLedgerFixtures.turnEntries(entityManager, workspace.getId(), running.getId()))
                .filteredOn(entry -> entry.getKind() != ConversationEntry.Kind.USER)
                .hasSize(2)
                .allSatisfy(copy -> assertThat(copy.isRedacted()).isTrue());
        assertThat(stored(asked.question()).getText()).isEqualTo("Deploy on Monday");
    }

    @Test
    void shouldEraseSearchResultsThatQuoteAMessageWhenTheMessageIsErased() {
        ConversationEntry secret =
                entries.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Rebase secret"));
        Conversation thread = conversations.openThread(workspace.getId(), secret.getId());
        ConversationTurn turn = runningTurn(thread);
        ConversationEntry result = ended(turn, searchResult(secret, "Rebase secret"));

        redaction.redact(List.of(secret.getId()));

        assertThat(stored(result).isRedacted()).isTrue();
        assertThat(payload(result).toString()).doesNotContain("Rebase secret").contains(ConversationRedaction.ERASED);
        assertThat(payload(result).path("message").path("toolCallId").asString())
                .isEqualTo("search-1");
    }

    @Test
    void shouldEraseANewSearchResultWhenItQuotesAMessageErasedSinceTheSearch() {
        ConversationEntry secret =
                entries.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Rebase secret"));
        ConversationTurn turn = runningTurn(main);
        redaction.redact(List.of(secret.getId()));

        ConversationEntry result = ended(turn, searchResult(secret, "Rebase secret"));

        assertThat(stored(result).isRedacted()).isTrue();
        assertThat(payload(result).toString()).doesNotContain("Rebase secret");
    }

    @Test
    void shouldRedactTheEntryAndEverySummaryThatCoversItWhenAMessageIsErased() {
        ConversationEntry early =
                entries.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Early secret"));
        ConversationEntry kept =
                entries.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Kept"));
        ConversationEntry covering =
                entries.append(workspace.getId(), main.getId(), summary("S1", ConversationLedgerFixtures.piIdOf(kept)));
        ConversationEntry thread =
                entries.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Parent"));
        Conversation side = conversations.openThread(workspace.getId(), thread.getId());
        ConversationEntry sideReply =
                entries.append(workspace.getId(), side.getId(), ConversationLedgerFixtures.hephaestus("Side"));
        ConversationEntry sideSummary = entries.append(
                workspace.getId(), side.getId(), summary("S2", ConversationLedgerFixtures.piIdOf(sideReply)));
        assertThat(payload(covering).path("fromSeq").asLong()).isEqualTo(early.getSeq());
        assertThat(payload(covering).path("toSeq").asLong()).isEqualTo(early.getSeq());

        redaction.redact(List.of(early.getId()));

        assertThat(stored(early).isRedacted()).isTrue();
        assertThat(stored(early).getText()).isEmpty();
        assertThat(payload(early).toString()).doesNotContain("Early secret").contains(ConversationRedaction.ERASED);
        assertThat(stored(covering).isRedacted()).isTrue();
        assertThat(stored(covering).getPayload()).isNull();
        // A thread's summary may have read the main conversation, so it goes too.
        assertThat(stored(sideSummary).isRedacted()).isTrue();
        assertThat(stored(kept).isRedacted()).isFalse();
        assertThat(stored(sideReply).isRedacted()).isFalse();
    }

    @Test
    void shouldEraseANewSummaryWhenItCoversAMessageErasedDuringItsAttempt() {
        ConversationEntry early =
                entries.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Early secret"));
        ConversationEntry kept =
                entries.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Kept"));
        ConversationTurn turn = runningTurn(main);
        redaction.redact(List.of(early.getId()));

        ConversationEntry written = ended(turn, compaction("Early secret", kept));

        assertThat(stored(written).getKind()).isEqualTo(ConversationEntry.Kind.SUMMARY);
        assertThat(stored(written).isRedacted()).isTrue();
        assertThat(stored(written).getText()).isEmpty();
    }

    @Test
    void shouldKeepANewSummaryWhenTheMessagesItCoversWereErasedBeforeItsAttempt() {
        ConversationEntry early =
                entries.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Early secret"));
        ConversationEntry kept =
                entries.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Kept"));
        redaction.redact(List.of(early.getId()));
        ConversationTurn turn = runningTurn(main);

        ConversationEntry written = ended(turn, compaction("Nothing secret", kept));

        assertThat(stored(written).isRedacted()).isFalse();
        assertThat(stored(written).getText()).isEqualTo("Nothing secret");
    }

    @Test
    void shouldEraseEveryEntryAndTellEachConversationOnceWhenAnErasureNamesMoreEntriesThanABindLimit() {
        Conversation thread = conversations.openThread(
                workspace.getId(),
                entries.append(workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Parent"))
                        .getId());
        // PostgreSQL refuses a statement with more than 65,535 bind parameters.
        long first = entryRepository.findMaxSeqByWorkspaceIdAndConversationId(workspace.getId(), main.getId()) + 1;
        int messages = 150;
        jdbcTemplate.update("""
                INSERT INTO conversation_entry (id, workspace_id, conversation_id, seq, kind, surface, text, payload,
                                          created_at)
                SELECT gen_random_uuid(), ?, ?, s, CASE WHEN s < ? THEN 'ANSWER' ELSE 'TOOL_RESULT' END, 'APP', 'secret',
                       jsonb_build_object('type', 'message', 'id', 'pi-' || s, 'message',
                           jsonb_build_object('role', 'toolResult', 'toolName', 'fetch_context', 'toolCallId', 'c' || s,
                               'content', jsonb_build_array(jsonb_build_object('type', 'text', 'text', 'secret')))),
                       now()
                FROM generate_series(?, ?) s
                """, workspace.getId(), main.getId(), first + messages, first, first + 70_000);
        ConversationEntry inThread =
                entries.append(workspace.getId(), thread.getId(), ConversationLedgerFixtures.hephaestus("Secret"));
        List<UUID> named = jdbcTemplate.query(
                "SELECT id FROM conversation_entry WHERE conversation_id IN (?, ?) AND text = 'secret' OR id = ?",
                (row, index) -> Objects.requireNonNull(row.getObject(1, UUID.class)),
                main.getId(),
                thread.getId(),
                inThread.getId());
        events.clear();

        redaction.redact(named);

        assertThat(named).hasSize(70_002);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM conversation_entry WHERE conversation_id IN (?, ?) AND redacted_at IS NOT NULL"
                                + " AND text = '' AND payload::text NOT ILIKE '%secret%'",
                        Long.class, main.getId(), thread.getId()))
                .isEqualTo(70_002);
        // Only messages change what an open conversation shows, a hundred to a notification.
        assertThat(events.stream(ConversationLedgerEvents.EntryChanged.class))
                .extracting(
                        change -> change.conversationId(),
                        change -> change.entryIds().size())
                .containsExactlyInAnyOrder(
                        tuple(main.getId(), ConversationLedgerEvents.EntryChanged.MAX_IDS),
                        tuple(main.getId(), messages - ConversationLedgerEvents.EntryChanged.MAX_IDS),
                        tuple(thread.getId(), 1));
    }

    @Test
    void shouldEraseANewThreadSummaryWhenAMainEntryItsParentFollowsIsErasedDuringItsAttempt() {
        ConversationTurnService.Submission asked = ask(main, "First question");
        ConversationTurnService.Submission followUp = ask(main, "Follow-up asked while the first ran");
        ConversationTurn first = claim(asked);
        ConversationEntry answer =
                ended(first, ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), "Secret answer"));
        ended(claim(followUp), ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), "Second answer"));
        // The thread's path reads the first turn's answer before its parent, although the answer has a later seq.
        assertThat(answer.getSeq()).isGreaterThan(followUp.question().getSeq());
        Conversation thread =
                conversations.openThread(workspace.getId(), followUp.question().getId());
        ConversationTurnService.Submission inThread = ask(thread, "Thread question");
        ConversationTurn turn = claim(inThread);
        redaction.redact(List.of(answer.getId()));

        ConversationEntry written = ended(turn, compaction("Secret answer", inThread.question()));

        assertThat(stored(written).getKind()).isEqualTo(ConversationEntry.Kind.SUMMARY);
        assertThat(stored(written).isRedacted()).isTrue();
    }

    @Test
    void shouldEraseEverySlackMessageAndStopSlackTurnsWhenSlackContentIsErased() {
        conversations.bindSlackIdentity(workspace.getId(), main.getId(), "T1", "U1", "D1");
        ConversationTurnService.Submission slack = slackQuestion("From Slack", "3.0");
        ConversationTurnService.Submission app = turns.submit(
                workspace.getId(),
                main.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP,
                        "From the app",
                        UUID.randomUUID().toString(),
                        null,
                        null));

        slackContent.eraseWorkspace(workspace.getId());

        assertThat(stored(slack.question()).isRedacted()).isTrue();
        assertThat(stored(app.question()).isRedacted()).isFalse();
        assertThat(turnRepository.findById(slack.turn().getId()).orElseThrow().getStatus())
                .isEqualTo(ConversationTurn.Status.STOPPED);
        assertThat(turnRepository.findById(app.turn().getId()).orElseThrow().getStatus())
                .isEqualTo(ConversationTurn.Status.QUEUED);
        assertThat(conversationRepository.findById(main.getId()).orElseThrow().getSlackUserId())
                .isNull();
    }

    @Test
    void shouldFindOnlyMessagesWhenTheTreeAlsoHoldsToolResultsSummariesAndRedactedEntries() {
        ConversationEntry answer = entries.append(
                workspace.getId(),
                main.getId(),
                ConversationLedgerFixtures.hephaestus("Rebase before the release branch"));
        ConversationEntry redacted = entries.append(
                workspace.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Rebase secret plan"));
        redaction.redact(List.of(redacted.getId()));
        Conversation thread = conversations.openThread(workspace.getId(), answer.getId());
        ConversationEntry inThread = entries.append(
                workspace.getId(), thread.getId(), ConversationLedgerFixtures.hephaestus("We agreed to rebase weekly"));
        ConversationTurn turn = runningTurn(thread);
        ended(
                turn,
                ConversationLedgerFixtures.toolResult(
                        ConversationLedgerFixtures.piId(), "c1", "fetch_context", "rebase rebase rebase", false));
        entries.append(
                workspace.getId(), main.getId(), summary("Rebase summary", ConversationLedgerFixtures.piIdOf(answer)));

        List<ConversationEntryService.SearchHit> all = entries.search(
                workspace.getId(),
                thread.getId(),
                new ConversationEntryService.SearchQuery(
                        "rebase", ConversationEntryService.SearchScope.ALL, null, null, 20));
        List<ConversationEntryService.SearchHit> mainOnly = entries.search(
                workspace.getId(),
                thread.getId(),
                new ConversationEntryService.SearchQuery(
                        "rebase", ConversationEntryService.SearchScope.MAIN, null, null, 20));
        List<ConversationEntryService.SearchHit> recent = entries.search(
                workspace.getId(),
                thread.getId(),
                new ConversationEntryService.SearchQuery(
                        null, ConversationEntryService.SearchScope.THIS, null, null, 20));

        assertThat(all)
                .extracting(ConversationEntryService.SearchHit::entryId)
                .containsExactlyInAnyOrder(answer.getId(), inThread.getId());
        assertThat(all).allSatisfy(hit -> assertThat(hit.excerpt()).containsIgnoringCase("rebase"));
        assertThat(mainOnly)
                .extracting(ConversationEntryService.SearchHit::entryId)
                .containsExactly(answer.getId());
        assertThat(recent)
                .extracting(ConversationEntryService.SearchHit::kind)
                .containsOnly(ConversationEntry.Kind.ANSWER, ConversationEntry.Kind.USER);
    }

    @Test
    void shouldFindTheWordsOfALinkCallThatSucceededWhenTheAnswerHasNoOtherText() {
        LinkAnswers answers = linkAnswers();

        List<ConversationEntryService.SearchHit> linked = search("fixup");

        assertThat(linked)
                .extracting(ConversationEntryService.SearchHit::entryId)
                .containsExactly(answers.shown());
        assertThat(linked.getFirst().excerpt()).contains("Squash the fixup commits");
        assertThat(search("flaky")).isEmpty();
        assertThat(search("ponderosa")).isEmpty();
        assertThat(search("helper")).isEmpty();
    }

    @Test
    void shouldExportTheWordsOfALinkCallThatSucceededAndEraseThemWithTheAnswer() {
        LinkAnswers answers = linkAnswers();

        List<JsonNode> exported = exportOwnEntries();

        assertThat(exported)
                .filteredOn(
                        row -> row.path("id").asString().equals(answers.shown().toString()))
                .singleElement()
                .satisfies(row -> assertThat(row.path("text").asString()).isEqualTo("Squash the fixup commits"));
        assertThat(exported)
                .filteredOn(
                        row -> row.path("id").asString().equals(answers.failed().toString()))
                .singleElement()
                .satisfies(row -> assertThat(row.path("text").asString()).isEmpty());
        assertThat(exported.toString()).doesNotContain("flaky", "ponderosa", "helper");

        redaction.redact(List.of(answers.shown()));

        assertThat(search("fixup")).isEmpty();
        assertThat(exportOwnEntries().toString()).doesNotContain("fixup");
    }

    @Test
    void shouldSearchThroughTheFullTextIndexWhenTheConversationHoldsManyMessages() throws Exception {
        LinkAnswers answers = linkAnswers();
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbcTemplate);
        named.update(
                """
                INSERT INTO conversation_entry(id,workspace_id,conversation_id,seq,kind,surface,text,search_text,created_at)
                SELECT gen_random_uuid(), :workspaceId, :conversationId, 100000 + n, 'ANSWER', 'APP',
                       'filler message ' || n, 'filler message ' || n, now()
                FROM generate_series(1, 5000) AS n
                """,
                new MapSqlParameterSource()
                        .addValue("workspaceId", workspace.getId())
                        .addValue("conversationId", main.getId()));
        schemaOwner.execute("ANALYZE conversation_entry");
        String search = ConversationEntryRepository.class
                .getMethod(
                        "searchByWorkspaceId",
                        Long.class,
                        Collection.class,
                        String.class,
                        Instant.class,
                        Instant.class,
                        int.class)
                .getAnnotation(Query.class)
                .value();

        List<@Nullable String> plan = named.queryForList(
                "EXPLAIN " + search,
                new MapSqlParameterSource()
                        .addValue("workspaceId", workspace.getId())
                        .addValue("conversationIds", List.of(main.getId()))
                        .addValue("query", "fixup", Types.VARCHAR)
                        .addValue("before", null, Types.TIMESTAMP_WITH_TIMEZONE)
                        .addValue("after", null, Types.TIMESTAMP_WITH_TIMEZONE)
                        .addValue("limit", 20),
                String.class);

        assertThat(String.join("\n", plan)).contains("ix_conversation_entry_text_search");
        assertThat(search("fixup"))
                .extracting(ConversationEntryService.SearchHit::entryId)
                .containsExactly(answers.shown());
    }

    /** Two of Hephaestus's answers in one turn that have no text blocks. */
    private record LinkAnswers(UUID shown, UUID failed) {}

    /**
     * Ends a turn with an answer whose only content is a {@code link_result} call that succeeded, and an answer that
     * thinks and calls {@code link_problem}, which fails.
     */
    private LinkAnswers linkAnswers() {
        ConversationTurn turn = runningTurn(main);
        ArrayNode shownContent = JsonNodeFactory.instance.arrayNode();
        shownContent
                .addObject()
                .put("type", "toolCall")
                .put("id", "link-shown")
                .put("name", "link_result")
                .putObject("arguments")
                .put("resultId", UUID.randomUUID().toString())
                .put("text", "Squash the fixup commits");
        ArrayNode failedContent = JsonNodeFactory.instance.arrayNode();
        failedContent.addObject().put("type", "thinking").put("thinking", "The ponderosa pipeline is slow");
        failedContent
                .addObject()
                .put("type", "toolCall")
                .put("id", "link-failed")
                .put("name", "link_problem")
                .putObject("arguments")
                .put("problemId", UUID.randomUUID().toString())
                .put("text", "Rename the flaky helper");
        turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new ConversationTurnService.Ending(
                        ConversationTurn.Status.DONE,
                        List.of(
                                ConversationLedgerFixtures.message(
                                        ConversationLedgerFixtures.piId(), "assistant", shownContent),
                                ConversationLedgerFixtures.toolResult(
                                        ConversationLedgerFixtures.piId(), "link-shown", "link_result", "Shown", false),
                                ConversationLedgerFixtures.message(
                                        ConversationLedgerFixtures.piId(), "assistant", failedContent),
                                ConversationLedgerFixtures.toolResult(
                                        ConversationLedgerFixtures.piId(),
                                        "link-failed",
                                        "link_problem",
                                        "No such problem",
                                        true)),
                        null,
                        null));
        List<ConversationEntry> answers = entryRepository.findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(
                workspace.getId(), turn.getId(), ConversationEntry.Kind.ANSWER);
        assertThat(answers).extracting(ConversationEntry::getText).containsExactly("", "");
        return new LinkAnswers(answers.getFirst().getId(), answers.getLast().getId());
    }

    private List<ConversationEntryService.SearchHit> search(String query) {
        return entries.search(
                workspace.getId(),
                main.getId(),
                new ConversationEntryService.SearchQuery(
                        query, ConversationEntryService.SearchScope.ALL, null, null, 20));
    }

    /** The developer's own Hephaestus messages as the person-data export writes them. */
    private List<JsonNode> exportOwnEntries() {
        PersonDataContributor store = personDataCatalog.contributors().stream()
                .filter(contributor -> contributor.store().equals("conversation_entry"))
                .findFirst()
                .orElseThrow();
        PersonScope scope = new PersonScope(developerAccountId, List.of(), List.of());
        return store.export(store.select(scope), scope);
    }

    private ConversationTurnService.Submission slackQuestion(String text, String ts) {
        return turns.submit(
                workspace.getId(),
                main.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.SLACK, text, "slack:T:D:" + ts, ts, null));
    }

    private ConversationTurn runningTurn(Conversation conversation) {
        return claim(ask(conversation, "What did we decide?"));
    }

    private ConversationTurnService.Submission ask(Conversation conversation, String text) {
        return turns.submit(
                workspace.getId(),
                conversation.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
    }

    /** Ends the turn with the Pi entry its host appended, and returns the stored entry. */
    private ConversationEntry ended(ConversationTurn turn, JsonNode piEntry) {
        turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new ConversationTurnService.Ending(ConversationTurn.Status.DONE, List.of(piEntry), null, null));
        return ConversationLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId())
                .getLast();
    }

    private ConversationTurn claim(ConversationTurnService.Submission submission) {
        ConversationTurn claimed = claims.claimNext().orElseThrow();
        assertThat(claimed.getId()).isEqualTo(submission.turn().getId());
        return claimed;
    }

    /** A {@code search_conversation} result as the end of a turn carries it: the worker's hits as JSON text. */
    private static ObjectNode searchResult(ConversationEntry hit, String excerpt) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.putArray("hits")
                .addObject()
                .put("entryId", hit.getId().toString())
                .put("branch", "main")
                .put("excerpt", excerpt);
        return ConversationLedgerFixtures.toolResult(
                ConversationLedgerFixtures.piId(), "search-1", "search_conversation", response.toString(), false);
    }

    /** A compaction the host appends that keeps the conversation from {@code firstKept} on. */
    private static ObjectNode compaction(String summary, ConversationEntry firstKept) {
        return ConversationLedgerFixtures.compaction(
                ConversationLedgerFixtures.piId(), summary, ConversationLedgerFixtures.piIdOf(firstKept));
    }

    private static ConversationEntryDraft summary(String text, String firstKeptEntryId) {
        return new ConversationEntryDraft(
                ConversationEntry.Kind.SUMMARY,
                ConversationEntry.Surface.APP,
                text,
                ConversationLedgerFixtures.compaction(ConversationLedgerFixtures.piId(), text, firstKeptEntryId),
                null,
                null,
                null);
    }
}
