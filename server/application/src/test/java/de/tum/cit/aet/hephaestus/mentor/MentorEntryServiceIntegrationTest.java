package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.mentor.adapter.MentorPersonDataCatalog;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.EntityManager;
import java.sql.Types;
import java.time.Duration;
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
class MentorEntryServiceIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    private static final String WORKER = "worker-entries";

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorConversationRepository conversationRepository;

    @Autowired
    private MentorEntryService entries;

    @Autowired
    private MentorEntryRepository entryRepository;

    @Autowired
    private MentorRedaction redaction;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private SlackWorkspaceContentEraser slackContent;

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MentorPersonDataCatalog personDataCatalog;

    private Workspace workspace;
    private MentorConversation main;
    private long developerAccountId;

    @BeforeEach
    void setUpLedger() {
        User developer = persistUser("heph-entries-developer");
        workspace = createWorkspace("heph-entries", "Hephaestus", "heph-org", AccountType.ORG, developer);
        developerAccountId = accountId(developer);
        main = conversations.findOrCreateMain(workspace.getId(), developerAccountId);
    }

    private MentorEntry stored(MentorEntry entry) {
        return entryRepository.findById(entry.getId()).orElseThrow();
    }

    private JsonNode payload(MentorEntry entry) {
        return Objects.requireNonNull(stored(entry).getPayload());
    }

    @Test
    void shouldRejectARequestIdWhenAnotherConversationAlreadyUsedIt() {
        MentorEntry answer = entries.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Answer"));
        MentorConversation thread = conversations.openThread(workspace.getId(), answer.getId());
        entries.appendOnce(workspace.getId(), main.getId(), MentorLedgerFixtures.digest("wd-2"));

        assertThatThrownBy(() ->
                        entries.appendOnce(workspace.getId(), thread.getId(), MentorLedgerFixtures.digest("wd-2")))
                .isInstanceOfSatisfying(
                        MentorRejectedException.class,
                        rejected -> assertThat(rejected.reason())
                                .isEqualTo(MentorRejectedException.Reason.REQUEST_ID_REUSED));
    }

    @Test
    void shouldKeepNoCopyOfTheEarlierWordsWhenTheDeveloperEditsASlackMessageAfterItsTurnRan() {
        MentorTurnService.Submission submission = slackQuestion("My token is tk-123", "1.5");
        MentorTurn turn = claim(submission);
        ended(turn, MentorLedgerFixtures.user(MentorLedgerFixtures.piId(), "[Slack] My token is tk-123"));

        entries.editSlackMessage(workspace.getId(), main.getId(), "1.5", "My token is gone");

        MentorEntry edited = stored(submission.question());
        assertThat(edited.getText()).isEqualTo("My token is gone");
        assertThat(payload(edited).toString()).doesNotContain("tk-123").contains("My token is gone");
    }

    @Test
    void shouldKeepNoCopyOfTheEarlierWordsWhenTheHostStoresAQuestionEditedSinceItsContextWasBuilt() {
        MentorTurnService.Submission submission = slackQuestion("My token is tk-456", "1.6");
        MentorTurn turn = claim(submission);
        entries.editSlackMessage(workspace.getId(), main.getId(), "1.6", "My token is gone");

        ended(turn, MentorLedgerFixtures.user(MentorLedgerFixtures.piId(), "[Slack] My token is tk-456"));

        assertThat(payload(submission.question()).toString())
                .doesNotContain("tk-456")
                .contains("My token is gone");
    }

    @Test
    void shouldEraseEveryCopyOfTheEarlierWordsButKeepTheMessageWhenTheDeveloperEditsASlackMessage() {
        MentorTurnService.Submission asked = slackQuestion("Deploy on Friday", "2.1");
        ended(claim(asked), MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "Fine"));
        MentorEntry kept = entries.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Kept"));
        MentorEntry covering =
                entries.append(workspace.getId(), main.getId(), summary("Friday", MentorLedgerFixtures.piIdOf(kept)));
        MentorEntry quote = ended(runningTurn(main), searchResult(asked.question(), "Deploy on Friday"));

        entries.editSlackMessage(workspace.getId(), main.getId(), "2.1", "Deploy on Monday");

        assertThat(stored(covering).isRedacted()).isTrue();
        assertThat(stored(quote).isRedacted()).isTrue();
        assertThat(payload(quote).toString()).doesNotContain("Friday");
        MentorEntry edited = stored(asked.question());
        assertThat(edited.isRedacted()).isFalse();
        assertThat(edited.getText()).isEqualTo("Deploy on Monday");
        assertThat(stored(kept).isRedacted()).isFalse();
    }

    @Test
    void shouldEraseWhatARunningTurnCopiedOfASlackMessageWhenTheDeveloperEditsItMeanwhile() {
        MentorTurnService.Submission asked = slackQuestion("Deploy on Friday", "2.2");
        ended(claim(asked), MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "Fine"));
        MentorEntry kept = entries.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Kept"));
        MentorTurn running = runningTurn(main);

        entries.editSlackMessage(workspace.getId(), main.getId(), "2.2", "Deploy on Monday");
        turns.end(
                running.getId(),
                running.getAttempt(),
                WORKER,
                MentorTurn.Status.DONE,
                List.of(compaction("Friday", kept), searchResult(asked.question(), "Deploy on Friday")),
                null);

        assertThat(MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), running.getId()))
                .filteredOn(entry -> entry.getKind() != MentorEntry.Kind.USER)
                .hasSize(2)
                .allSatisfy(copy -> assertThat(copy.isRedacted()).isTrue());
        assertThat(stored(asked.question()).getText()).isEqualTo("Deploy on Monday");
    }

    @Test
    void shouldEraseSearchResultsThatQuoteAMessageWhenTheMessageIsErased() {
        MentorEntry secret =
                entries.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Rebase secret"));
        MentorConversation thread = conversations.openThread(workspace.getId(), secret.getId());
        MentorTurn turn = runningTurn(thread);
        MentorEntry result = ended(turn, searchResult(secret, "Rebase secret"));

        redaction.redact(List.of(secret.getId()));

        assertThat(stored(result).isRedacted()).isTrue();
        assertThat(payload(result).toString()).doesNotContain("Rebase secret").contains(MentorRedaction.ERASED);
        assertThat(payload(result).path("message").path("toolCallId").asString())
                .isEqualTo("search-1");
    }

    @Test
    void shouldEraseANewSearchResultWhenItQuotesAMessageErasedSinceTheSearch() {
        MentorEntry secret =
                entries.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Rebase secret"));
        MentorTurn turn = runningTurn(main);
        redaction.redact(List.of(secret.getId()));

        MentorEntry result = ended(turn, searchResult(secret, "Rebase secret"));

        assertThat(stored(result).isRedacted()).isTrue();
        assertThat(payload(result).toString()).doesNotContain("Rebase secret");
    }

    @Test
    void shouldRedactTheEntryAndEverySummaryThatCoversItWhenAMessageIsErased() {
        MentorEntry early = entries.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Early secret"));
        MentorEntry kept = entries.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Kept"));
        MentorEntry covering =
                entries.append(workspace.getId(), main.getId(), summary("S1", MentorLedgerFixtures.piIdOf(kept)));
        MentorEntry thread = entries.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Parent"));
        MentorConversation side = conversations.openThread(workspace.getId(), thread.getId());
        MentorEntry sideReply = entries.append(workspace.getId(), side.getId(), MentorLedgerFixtures.heph("Side"));
        MentorEntry sideSummary =
                entries.append(workspace.getId(), side.getId(), summary("S2", MentorLedgerFixtures.piIdOf(sideReply)));
        assertThat(payload(covering).path("fromSeq").asLong()).isEqualTo(early.getSeq());
        assertThat(payload(covering).path("toSeq").asLong()).isEqualTo(early.getSeq());

        redaction.redact(List.of(early.getId()));

        assertThat(stored(early).isRedacted()).isTrue();
        assertThat(stored(early).getText()).isEmpty();
        assertThat(payload(early).toString()).doesNotContain("Early secret").contains(MentorRedaction.ERASED);
        assertThat(stored(covering).isRedacted()).isTrue();
        assertThat(stored(covering).getPayload()).isNull();
        // A thread's summary may have read the main conversation, so it goes too.
        assertThat(stored(sideSummary).isRedacted()).isTrue();
        assertThat(stored(kept).isRedacted()).isFalse();
        assertThat(stored(sideReply).isRedacted()).isFalse();
    }

    @Test
    void shouldEraseANewSummaryWhenItCoversAMessageErasedDuringItsAttempt() {
        MentorEntry early = entries.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Early secret"));
        MentorEntry kept = entries.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Kept"));
        MentorTurn turn = runningTurn(main);
        redaction.redact(List.of(early.getId()));

        MentorEntry written = ended(turn, compaction("Early secret", kept));

        assertThat(stored(written).getKind()).isEqualTo(MentorEntry.Kind.SUMMARY);
        assertThat(stored(written).isRedacted()).isTrue();
        assertThat(stored(written).getText()).isEmpty();
    }

    @Test
    void shouldKeepANewSummaryWhenTheMessagesItCoversWereErasedBeforeItsAttempt() {
        MentorEntry early = entries.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Early secret"));
        MentorEntry kept = entries.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Kept"));
        redaction.redact(List.of(early.getId()));
        MentorTurn turn = runningTurn(main);

        MentorEntry written = ended(turn, compaction("Nothing secret", kept));

        assertThat(stored(written).isRedacted()).isFalse();
        assertThat(stored(written).getText()).isEqualTo("Nothing secret");
    }

    @Test
    void shouldEraseEveryEntryAndTellEachConversationOnceWhenAnErasureNamesMoreEntriesThanABindLimit() {
        MentorConversation thread = conversations.openThread(
                workspace.getId(),
                entries.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Parent"))
                        .getId());
        // PostgreSQL refuses a statement with more than 65,535 bind parameters.
        long first = entryRepository.findMaxSeqByWorkspaceIdAndConversationId(workspace.getId(), main.getId()) + 1;
        int messages = 150;
        jdbcTemplate.update("""
                INSERT INTO mentor_entry (id, workspace_id, conversation_id, seq, kind, surface, text, payload,
                                          slack_attempts, created_at)
                SELECT gen_random_uuid(), ?, ?, s, CASE WHEN s < ? THEN 'HEPH' ELSE 'TOOL_RESULT' END, 'APP', 'secret',
                       jsonb_build_object('type', 'message', 'id', 'pi-' || s, 'message',
                           jsonb_build_object('role', 'toolResult', 'toolName', 'fetch_context', 'toolCallId', 'c' || s,
                               'content', jsonb_build_array(jsonb_build_object('type', 'text', 'text', 'secret')))),
                       0, now()
                FROM generate_series(?, ?) s
                """, workspace.getId(), main.getId(), first + messages, first, first + 70_000);
        MentorEntry inThread = entries.append(workspace.getId(), thread.getId(), MentorLedgerFixtures.heph("Secret"));
        List<UUID> named = jdbcTemplate.query(
                "SELECT id FROM mentor_entry WHERE conversation_id IN (?, ?) AND text = 'secret' OR id = ?",
                (row, index) -> Objects.requireNonNull(row.getObject(1, UUID.class)),
                main.getId(),
                thread.getId(),
                inThread.getId());
        events.clear();

        redaction.redact(named);

        assertThat(named).hasSize(70_002);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM mentor_entry WHERE conversation_id IN (?, ?) AND redacted_at IS NOT NULL"
                                + " AND text = '' AND payload::text NOT ILIKE '%secret%'",
                        Long.class, main.getId(), thread.getId()))
                .isEqualTo(70_002);
        // Only messages change what an open conversation shows, a hundred to a notification.
        assertThat(events.stream(MentorLedgerEvents.EntryChanged.class))
                .extracting(
                        change -> change.conversationId(),
                        change -> change.entryIds().size())
                .containsExactlyInAnyOrder(
                        tuple(main.getId(), MentorLedgerEvents.EntryChanged.MAX_IDS),
                        tuple(main.getId(), messages - MentorLedgerEvents.EntryChanged.MAX_IDS),
                        tuple(thread.getId(), 1));
    }

    @Test
    void shouldEraseANewThreadSummaryWhenAMainEntryItsParentFollowsIsErasedDuringItsAttempt() {
        MentorTurnService.Submission asked = ask(main, "First question");
        MentorTurnService.Submission followUp = ask(main, "Follow-up asked while the first ran");
        MentorTurn first = claim(asked);
        MentorEntry answer = ended(first, MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "Secret answer"));
        ended(claim(followUp), MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "Second answer"));
        // The thread's path reads the first turn's answer before its parent, although the answer has a later seq.
        assertThat(answer.getSeq()).isGreaterThan(followUp.question().getSeq());
        MentorConversation thread =
                conversations.openThread(workspace.getId(), followUp.question().getId());
        MentorTurnService.Submission inThread = ask(thread, "Thread question");
        MentorTurn turn = claim(inThread);
        redaction.redact(List.of(answer.getId()));

        MentorEntry written = ended(turn, compaction("Secret answer", inThread.question()));

        assertThat(stored(written).getKind()).isEqualTo(MentorEntry.Kind.SUMMARY);
        assertThat(stored(written).isRedacted()).isTrue();
    }

    @Test
    void shouldEraseEverySlackMessageAndStopSlackTurnsWhenSlackContentIsErased() {
        conversations.bindSlackIdentity(workspace.getId(), main.getId(), "T1", "U1", "D1");
        MentorTurnService.Submission slack = slackQuestion("From Slack", "3.0");
        MentorTurnService.Submission app = turns.submit(
                workspace.getId(),
                main.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP,
                        "From the app",
                        UUID.randomUUID().toString(),
                        null,
                        null));

        slackContent.eraseWorkspace(workspace.getId());

        assertThat(stored(slack.question()).isRedacted()).isTrue();
        assertThat(stored(app.question()).isRedacted()).isFalse();
        assertThat(turnRepository.findById(slack.turn().getId()).orElseThrow().getStatus())
                .isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(turnRepository.findById(app.turn().getId()).orElseThrow().getStatus())
                .isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(conversationRepository.findById(main.getId()).orElseThrow().getSlackUserId())
                .isNull();
    }

    @Test
    void shouldFindOnlyMessagesWhenTheTreeAlsoHoldsToolResultsSummariesAndRedactedEntries() {
        MentorEntry answer = entries.append(
                workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Rebase before the release branch"));
        MentorEntry redacted =
                entries.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Rebase secret plan"));
        redaction.redact(List.of(redacted.getId()));
        MentorConversation thread = conversations.openThread(workspace.getId(), answer.getId());
        MentorEntry inThread = entries.append(
                workspace.getId(), thread.getId(), MentorLedgerFixtures.heph("We agreed to rebase weekly"));
        MentorTurn turn = runningTurn(thread);
        ended(
                turn,
                MentorLedgerFixtures.toolResult(
                        MentorLedgerFixtures.piId(), "c1", "fetch_context", "rebase rebase rebase", false));
        entries.append(workspace.getId(), main.getId(), summary("Rebase summary", MentorLedgerFixtures.piIdOf(answer)));

        List<MentorEntryService.SearchHit> all = entries.search(
                workspace.getId(),
                thread.getId(),
                new MentorEntryService.SearchQuery("rebase", MentorEntryService.SearchScope.ALL, null, null, 20));
        List<MentorEntryService.SearchHit> mainOnly = entries.search(
                workspace.getId(),
                thread.getId(),
                new MentorEntryService.SearchQuery("rebase", MentorEntryService.SearchScope.MAIN, null, null, 20));
        List<MentorEntryService.SearchHit> recent = entries.search(
                workspace.getId(),
                thread.getId(),
                new MentorEntryService.SearchQuery(null, MentorEntryService.SearchScope.THIS, null, null, 20));

        assertThat(all)
                .extracting(MentorEntryService.SearchHit::entryId)
                .containsExactlyInAnyOrder(answer.getId(), inThread.getId());
        assertThat(all).allSatisfy(hit -> assertThat(hit.excerpt()).containsIgnoringCase("rebase"));
        assertThat(mainOnly).extracting(MentorEntryService.SearchHit::entryId).containsExactly(answer.getId());
        assertThat(recent)
                .extracting(MentorEntryService.SearchHit::kind)
                .containsOnly(MentorEntry.Kind.HEPH, MentorEntry.Kind.USER);
    }

    @Test
    void shouldFindTheWordsOfALinkCallThatSucceededWhenTheAnswerHasNoOtherText() {
        LinkAnswers answers = linkAnswers();

        List<MentorEntryService.SearchHit> linked = search("fixup");

        assertThat(linked).extracting(MentorEntryService.SearchHit::entryId).containsExactly(answers.shown());
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
                INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,kind,surface,text,search_text,created_at)
                SELECT gen_random_uuid(), :workspaceId, :conversationId, 100000 + n, 'HEPH', 'APP',
                       'filler message ' || n, 'filler message ' || n, now()
                FROM generate_series(1, 5000) AS n
                """,
                new MapSqlParameterSource()
                        .addValue("workspaceId", workspace.getId())
                        .addValue("conversationId", main.getId()));
        jdbcTemplate.execute("ANALYZE mentor_entry");
        String search = MentorEntryRepository.class
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

        assertThat(String.join("\n", plan)).contains("ix_mentor_entry_text_search");
        assertThat(search("fixup"))
                .extracting(MentorEntryService.SearchHit::entryId)
                .containsExactly(answers.shown());
    }

    /** Two of Hephaestus's answers in one turn that have no text blocks. */
    private record LinkAnswers(UUID shown, UUID failed) {}

    /**
     * Ends a turn with an answer whose only content is a {@code link_result} call that succeeded, and an answer that
     * thinks and calls {@code link_problem}, which fails.
     */
    private LinkAnswers linkAnswers() {
        MentorTurn turn = runningTurn(main);
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
                turn.getId(),
                turn.getAttempt(),
                WORKER,
                MentorTurn.Status.DONE,
                List.of(
                        MentorLedgerFixtures.message(MentorLedgerFixtures.piId(), "assistant", shownContent),
                        MentorLedgerFixtures.toolResult(
                                MentorLedgerFixtures.piId(), "link-shown", "link_result", "Shown", false),
                        MentorLedgerFixtures.message(MentorLedgerFixtures.piId(), "assistant", failedContent),
                        MentorLedgerFixtures.toolResult(
                                MentorLedgerFixtures.piId(), "link-failed", "link_problem", "No such problem", true)),
                null);
        List<MentorEntry> answers = entryRepository.findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(
                workspace.getId(), turn.getId(), MentorEntry.Kind.HEPH);
        assertThat(answers).extracting(MentorEntry::getText).containsExactly("", "");
        return new LinkAnswers(answers.getFirst().getId(), answers.getLast().getId());
    }

    private List<MentorEntryService.SearchHit> search(String query) {
        return entries.search(
                workspace.getId(),
                main.getId(),
                new MentorEntryService.SearchQuery(query, MentorEntryService.SearchScope.ALL, null, null, 20));
    }

    /** The developer's own Hephaestus messages as the person-data export writes them. */
    private List<JsonNode> exportOwnEntries() {
        PersonDataContributor store = personDataCatalog.contributors().stream()
                .filter(contributor -> contributor.store().equals("mentor_entry"))
                .findFirst()
                .orElseThrow();
        PersonScope scope = new PersonScope(developerAccountId, List.of(), List.of());
        return store.export(store.select(scope), scope);
    }

    private MentorTurnService.Submission slackQuestion(String text, String ts) {
        return turns.submit(
                workspace.getId(),
                main.getId(),
                new MentorTurnService.Question(MentorTurn.Surface.SLACK, text, "slack:T:D:" + ts, ts, null));
    }

    private MentorTurn runningTurn(MentorConversation conversation) {
        return claim(ask(conversation, "What did we decide?"));
    }

    private MentorTurnService.Submission ask(MentorConversation conversation, String text) {
        return turns.submit(
                workspace.getId(),
                conversation.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
    }

    /** Ends the turn with the Pi entry its host appended, and returns the stored entry. */
    private MentorEntry ended(MentorTurn turn, JsonNode piEntry) {
        turns.end(turn.getId(), turn.getAttempt(), WORKER, MentorTurn.Status.DONE, List.of(piEntry), null);
        return MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId())
                .getLast();
    }

    private MentorTurn claim(MentorTurnService.Submission submission) {
        MentorTurn claimed = turns.claimNext(WORKER, Duration.ofMinutes(5)).orElseThrow();
        assertThat(claimed.getId()).isEqualTo(submission.turn().getId());
        return claimed;
    }

    /** A {@code search_conversation} result as the end of a turn carries it: the worker's hits as JSON text. */
    private static ObjectNode searchResult(MentorEntry hit, String excerpt) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.putArray("hits")
                .addObject()
                .put("entryId", hit.getId().toString())
                .put("branch", "main")
                .put("excerpt", excerpt);
        return MentorLedgerFixtures.toolResult(
                MentorLedgerFixtures.piId(), "search-1", "search_conversation", response.toString(), false);
    }

    /** A compaction the host appends that keeps the conversation from {@code firstKept} on. */
    private static ObjectNode compaction(String summary, MentorEntry firstKept) {
        return MentorLedgerFixtures.compaction(
                MentorLedgerFixtures.piId(), summary, MentorLedgerFixtures.piIdOf(firstKept));
    }

    private static MentorEntryDraft summary(String text, String firstKeptEntryId) {
        return new MentorEntryDraft(
                MentorEntry.Kind.SUMMARY,
                MentorEntry.Surface.APP,
                text,
                MentorLedgerFixtures.compaction(MentorLedgerFixtures.piId(), text, firstKeptEntryId),
                null,
                null,
                null);
    }
}
