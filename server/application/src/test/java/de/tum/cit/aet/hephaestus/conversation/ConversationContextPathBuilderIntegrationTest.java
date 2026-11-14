package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class ConversationContextPathBuilderIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ConversationTurnClaims claims;

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private ConversationEntryRepository entryRepository;

    @Autowired
    private ConversationRedaction redaction;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationContextPathBuilder paths;

    private Workspace workspace;
    private Conversation main;

    @BeforeEach
    void setUpLedger() {
        User developer = persistUser("ask-path-developer");
        workspace = createWorkspace("ask-path", "Hephaestus", "ask-org", AccountType.ORG, developer);
        main = conversations.findOrCreateMain(workspace.getId(), accountId(developer));
    }

    /** Runs one turn to its end: the host's user entry, then {@code answers}, ended as {@code outcome}. */
    private ConversationTurn run(
            ConversationTurnService.Submission submission, ConversationTurn.Status outcome, String... answers) {
        ConversationTurn turn = claims.claimNext().orElseThrow();
        assertThat(turn.getId()).isEqualTo(submission.turn().getId());
        List<JsonNode> appended = new ArrayList<>();
        appended.add(ConversationLedgerFixtures.user(
                ConversationLedgerFixtures.piId(),
                paths.forTurn(workspace.getId(), turn.getId(), BERLIN).prompt()));
        for (String answer : answers) {
            appended.add(ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), answer));
        }
        turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new ConversationTurnService.Ending(outcome, appended, null, null));
        return turn;
    }

    private ConversationTurnService.Submission ask(Conversation conversation, String text) {
        return turns.submit(
                workspace.getId(),
                conversation.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
    }

    /** The path a host is seeded with for the next question of the main conversation. */
    private List<ObjectNode> mainPath() {
        return paths.forTurn(workspace.getId(), nextTurn().getId(), BERLIN).entries();
    }

    private ConversationTurn nextTurn() {
        ConversationTurnService.Submission next = ask(main, "Next question");
        ConversationTurn turn = claims.claimNext().orElseThrow();
        assertThat(turn.getId()).isEqualTo(next.turn().getId());
        return turn;
    }

    private static List<String> texts(List<ObjectNode> path) {
        return path.stream()
                .map(ConversationContextPathBuilderIntegrationTest::text)
                .toList();
    }

    private static String text(JsonNode entry) {
        if (entry.path("type").asString("").equals("compaction")) {
            return "summary:" + entry.path("summary").asString();
        }
        JsonNode content = entry.path("message").path("content");
        if (content.isString()) {
            return content.asString();
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            text.append(block.path("text").asString(""));
        }
        return text.toString();
    }

    @Test
    void shouldPlaceAFollowUpAfterTheAnswerItWaitedForWhenItWasAskedDuringTheTurn() {
        ConversationTurnService.Submission first = ask(main, "First");
        ConversationTurnService.Submission followUp = ask(main, "Follow-up");
        run(first, ConversationTurn.Status.DONE, "First answer");
        run(followUp, ConversationTurn.Status.DONE, "Second answer");

        List<String> path = texts(mainPath());

        assertThat(path).hasSize(4);
        assertThat(path.get(0)).endsWith(" First");
        assertThat(path.get(1)).isEqualTo("First answer");
        assertThat(path.get(2)).endsWith(" Follow-up");
        assertThat(path.get(3)).isEqualTo("Second answer");
    }

    @Test
    void shouldLinkEveryEntryToTheOneBeforeItAndRenderTheSameBytesWhenBuiltTwice() {
        run(ask(main, "Question"), ConversationTurn.Status.DONE, "Answer");

        ConversationTurn next = nextTurn();
        List<ObjectNode> path =
                paths.forTurn(workspace.getId(), next.getId(), BERLIN).entries();

        assertThat(path.getFirst().path("parentId").isNull()).isTrue();
        assertThat(path.get(1).path("parentId").asString())
                .isEqualTo(path.getFirst().path("id").asString());
        assertThat(paths.forTurn(workspace.getId(), next.getId(), BERLIN)
                        .entries()
                        .toString())
                .isEqualTo(path.toString());
    }

    @Test
    void shouldPrefixAQuestionWithItsTimeZoneAndSurfaceWhenTheHostSeedsIt() {
        ConversationTurnService.Submission submission = turns.submit(
                workspace.getId(),
                main.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.SLACK, "From Slack", "slack:T:D:9.0", "9.0", null));
        ConversationTurn turn = claims.claimNext().orElseThrow();

        ConversationContextPathBuilder.TurnContext context = paths.forTurn(workspace.getId(), turn.getId(), BERLIN);

        assertThat(context.sessionId()).isEqualTo(main.getId());
        assertThat(context.entries()).isEmpty();
        assertThat(context.questionEntryId()).isEqualTo(submission.question().getId());
        assertThat(context.prompt())
                .matches("\\[\\w{3} \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} Europe/Berlin · Slack] From Slack");
    }

    @Test
    void shouldStartAtTheFirstKeptEntryWhenASummaryReplacedEarlierEntries() {
        run(ask(main, "Old question"), ConversationTurn.Status.DONE, "Old answer");
        ConversationTurnService.Submission kept = ask(main, "Kept question");
        ConversationTurn turn = run(kept, ConversationTurn.Status.DONE, "Kept answer");
        ConversationEntry keptQuestion =
                entries.require(workspace.getId(), kept.question().getId());
        entries.append(
                workspace.getId(),
                main.getId(),
                summary("Old talk", ConversationLedgerFixtures.piIdOf(keptQuestion), turn.getId()));

        List<String> path = texts(mainPath());

        assertThat(path).hasSize(3);
        assertThat(path.get(0)).endsWith(" Kept question");
        assertThat(path.get(1)).isEqualTo("Kept answer");
        assertThat(path.get(2)).isEqualTo("summary:Old talk");
    }

    @Test
    void shouldStartAtTheNextKeptEntryWhenTheDeveloperDeletesTheQuestionASummaryKeepsFirst() {
        run(ask(main, "Old question"), ConversationTurn.Status.DONE, "Old answer");
        ConversationTurnService.Submission kept = ask(main, "Kept question");
        ConversationTurn turn = run(kept, ConversationTurn.Status.DONE, "Kept answer");
        ConversationEntry keptQuestion =
                entries.require(workspace.getId(), kept.question().getId());
        entries.append(
                workspace.getId(),
                main.getId(),
                summary("Old talk", ConversationLedgerFixtures.piIdOf(keptQuestion), turn.getId()));

        entries.deleteMessage(workspace.getId(), keptQuestion.getId());

        List<ObjectNode> path = mainPath();
        assertThat(texts(path)).containsExactly("Kept answer", "summary:Old talk");
        // Pi keeps the entries before a compaction only from the one it names.
        assertThat(path.get(1).path("firstKeptEntryId").asString())
                .isEqualTo(path.getFirst().path("id").asString());
    }

    @Test
    void shouldUseTheSummaryAtOrBeforeItsParentWhenAThreadIsBuilt() {
        run(ask(main, "Old question"), ConversationTurn.Status.DONE, "Old answer");
        ConversationTurnService.Submission parentQuestion = ask(main, "Parent question");
        run(parentQuestion, ConversationTurn.Status.DONE, "Parent answer");
        Conversation thread = conversations.openThread(
                workspace.getId(), parentQuestion.question().getId());
        // A later summary of the main conversation, written after the thread's parent.
        ConversationTurnService.Submission later = ask(main, "Later question");
        ConversationTurn laterTurn = run(later, ConversationTurn.Status.DONE, "Later answer");
        ConversationEntry laterQuestion =
                entries.require(workspace.getId(), later.question().getId());
        entries.append(
                workspace.getId(),
                main.getId(),
                summary("Main so far", ConversationLedgerFixtures.piIdOf(laterQuestion), laterTurn.getId()));
        ConversationTurnService.Submission inThread = ask(thread, "Thread question");
        ConversationTurn threadTurn = claims.claimNext().orElseThrow();

        ConversationContextPathBuilder.TurnContext context =
                paths.forTurn(workspace.getId(), threadTurn.getId(), BERLIN);

        assertThat(context.sessionId()).isEqualTo(thread.getId());
        assertThat(texts(context.entries()))
                .hasSize(3)
                .noneMatch(text -> text.contains("Later") || text.contains("Main so far"));
        assertThat(texts(context.entries()).getLast()).endsWith(" Parent question");
        assertThat(context.prompt()).endsWith(" Thread question");
        assertThat(context.questionEntryId()).isEqualTo(inThread.question().getId());
    }

    @Test
    void shouldMarkAStoppedAnswerAndTheQuestionOfAFailedTurnWhenTheyAreInThePath() {
        run(ask(main, "Stop me"), ConversationTurn.Status.STOPPED, "Half an answer");
        run(ask(main, "Fail me"), ConversationTurn.Status.FAILED, "Lost answer");

        List<String> path = texts(mainPath());

        assertThat(path).hasSize(3);
        assertThat(path.get(1)).isEqualTo("Half an answer" + ConversationContextPathBuilder.STOPPED_MARKER);
        assertThat(path.get(2))
                .endsWith(" Fail me\n\n" + ConversationContextPathBuilder.FAILED_MARKER)
                .doesNotContain("Lost answer");
    }

    @Test
    void shouldMarkTheQuestionWhenTheDeveloperStoppedATurnBeforeItRan() {
        ConversationTurnService.Submission submission = ask(main, "Never mind");
        turns.stop(workspace.getId(), submission.turn().getId());

        List<String> path = texts(mainPath());

        assertThat(path)
                .singleElement()
                .asString()
                .endsWith(" Never mind\n\n" + ConversationContextPathBuilder.STOPPED_MARKER);
    }

    @Test
    void shouldOmitADeletedQuestionButKeepAnErasedToolResultAsASkeletonWhenEntriesAreRedacted() {
        ConversationTurnService.Submission submission = ask(main, "Secret question");
        ConversationTurn turn = claims.claimNext().orElseThrow();
        turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new ConversationTurnService.Ending(
                        ConversationTurn.Status.DONE,
                        List.of(
                                ConversationLedgerFixtures.toolCall(
                                        ConversationLedgerFixtures.piId(),
                                        "call-1",
                                        "fetch_context",
                                        JsonNodeFactory.instance.objectNode().put("path", "user.json")),
                                ConversationLedgerFixtures.toolResult(
                                        ConversationLedgerFixtures.piId(),
                                        "call-1",
                                        "fetch_context",
                                        "colleague data",
                                        false)),
                        null,
                        null));
        ConversationEntry toolResult = entryRepository
                .findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(
                        workspace.getId(), turn.getId(), ConversationEntry.Kind.TOOL_RESULT)
                .getFirst();

        redaction.redact(List.of(toolResult.getId()));
        entries.deleteMessage(workspace.getId(), submission.question().getId());

        List<ObjectNode> path = mainPath();
        assertThat(path).hasSize(2);
        assertThat(path.toString()).doesNotContain("Secret question", "colleague data");
        assertThat(path.get(1).path("message").path("toolCallId").asString()).isEqualTo("call-1");
        assertThat(text(path.get(1))).isEqualTo(ConversationRedaction.ERASED);
    }

    private static ConversationEntryDraft summary(String text, String firstKeptEntryId, UUID turnId) {
        return new ConversationEntryDraft(
                ConversationEntry.Kind.SUMMARY,
                ConversationEntry.Surface.APP,
                text,
                ConversationLedgerFixtures.compaction(ConversationLedgerFixtures.piId(), text, firstKeptEntryId),
                turnId,
                null,
                null);
    }
}
