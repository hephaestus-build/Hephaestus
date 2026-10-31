package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Duration;
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

class MentorContextPathBuilderIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorEntryService entries;

    @Autowired
    private MentorEntryRepository entryRepository;

    @Autowired
    private MentorRedaction redaction;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorContextPathBuilder paths;

    private Workspace workspace;
    private MentorConversation main;

    @BeforeEach
    void setUpLedger() {
        User developer = persistUser("heph-path-developer");
        workspace = createWorkspace("heph-path", "Hephaestus", "heph-org", AccountType.ORG, developer);
        main = conversations.findOrCreateMain(workspace.getId(), accountId(developer));
    }

    /** Runs one turn to its end: the host's user entry, then {@code answers}, ended as {@code outcome}. */
    private MentorTurn run(MentorTurnService.Submission submission, MentorTurn.Status outcome, String... answers) {
        MentorTurn turn = turns.claimNext(LEASE).orElseThrow();
        assertThat(turn.getId()).isEqualTo(submission.turn().getId());
        List<JsonNode> appended = new ArrayList<>();
        appended.add(MentorLedgerFixtures.user(
                MentorLedgerFixtures.piId(),
                paths.forTurn(workspace.getId(), turn.getId(), BERLIN).prompt()));
        for (String answer : answers) {
            appended.add(MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), answer));
        }
        turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                new MentorTurnService.Ending(outcome, appended, null));
        return turn;
    }

    private MentorTurnService.Submission ask(MentorConversation conversation, String text) {
        return turns.submit(
                workspace.getId(),
                conversation.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
    }

    /** The path a host is seeded with for the next question of the main conversation. */
    private List<ObjectNode> mainPath() {
        return paths.forTurn(workspace.getId(), nextTurn().getId(), BERLIN).entries();
    }

    private MentorTurn nextTurn() {
        MentorTurnService.Submission next = ask(main, "Next question");
        MentorTurn turn = turns.claimNext(LEASE).orElseThrow();
        assertThat(turn.getId()).isEqualTo(next.turn().getId());
        return turn;
    }

    private static List<String> texts(List<ObjectNode> path) {
        return path.stream().map(MentorContextPathBuilderIntegrationTest::text).toList();
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
        MentorTurnService.Submission first = ask(main, "First");
        MentorTurnService.Submission followUp = ask(main, "Follow-up");
        run(first, MentorTurn.Status.DONE, "First answer");
        run(followUp, MentorTurn.Status.DONE, "Second answer");

        List<String> path = texts(mainPath());

        assertThat(path).hasSize(4);
        assertThat(path.get(0)).endsWith(" First");
        assertThat(path.get(1)).isEqualTo("First answer");
        assertThat(path.get(2)).endsWith(" Follow-up");
        assertThat(path.get(3)).isEqualTo("Second answer");
    }

    @Test
    void shouldLinkEveryEntryToTheOneBeforeItAndRenderTheSameBytesWhenBuiltTwice() {
        run(ask(main, "Question"), MentorTurn.Status.DONE, "Answer");

        MentorTurn next = nextTurn();
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
        MentorTurnService.Submission submission = turns.submit(
                workspace.getId(),
                main.getId(),
                new MentorTurnService.Question(MentorTurn.Surface.SLACK, "From Slack", "slack:T:D:9.0", "9.0", null));
        MentorTurn turn = turns.claimNext(LEASE).orElseThrow();

        MentorContextPathBuilder.TurnContext context = paths.forTurn(workspace.getId(), turn.getId(), BERLIN);

        assertThat(context.sessionId()).isEqualTo(main.getId());
        assertThat(context.entries()).isEmpty();
        assertThat(context.questionEntryId()).isEqualTo(submission.question().getId());
        assertThat(context.prompt())
                .matches("\\[\\w{3} \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} Europe/Berlin · Slack] From Slack");
    }

    @Test
    void shouldStartAtTheFirstKeptEntryWhenASummaryReplacedEarlierEntries() {
        run(ask(main, "Old question"), MentorTurn.Status.DONE, "Old answer");
        MentorTurnService.Submission kept = ask(main, "Kept question");
        MentorTurn turn = run(kept, MentorTurn.Status.DONE, "Kept answer");
        MentorEntry keptQuestion =
                entries.require(workspace.getId(), kept.question().getId());
        entries.append(
                workspace.getId(),
                main.getId(),
                summary("Old talk", MentorLedgerFixtures.piIdOf(keptQuestion), turn.getId()));

        List<String> path = texts(mainPath());

        assertThat(path).hasSize(3);
        assertThat(path.get(0)).endsWith(" Kept question");
        assertThat(path.get(1)).isEqualTo("Kept answer");
        assertThat(path.get(2)).isEqualTo("summary:Old talk");
    }

    @Test
    void shouldStartAtTheNextKeptEntryWhenTheDeveloperDeletesTheQuestionASummaryKeepsFirst() {
        run(ask(main, "Old question"), MentorTurn.Status.DONE, "Old answer");
        MentorTurnService.Submission kept = ask(main, "Kept question");
        MentorTurn turn = run(kept, MentorTurn.Status.DONE, "Kept answer");
        MentorEntry keptQuestion =
                entries.require(workspace.getId(), kept.question().getId());
        entries.append(
                workspace.getId(),
                main.getId(),
                summary("Old talk", MentorLedgerFixtures.piIdOf(keptQuestion), turn.getId()));

        entries.deleteMessage(workspace.getId(), keptQuestion.getId());

        List<ObjectNode> path = mainPath();
        assertThat(texts(path)).containsExactly("Kept answer", "summary:Old talk");
        // Pi keeps the entries before a compaction only from the one it names.
        assertThat(path.get(1).path("firstKeptEntryId").asString())
                .isEqualTo(path.getFirst().path("id").asString());
    }

    @Test
    void shouldUseTheSummaryAtOrBeforeItsParentWhenAThreadIsBuilt() {
        run(ask(main, "Old question"), MentorTurn.Status.DONE, "Old answer");
        MentorTurnService.Submission parentQuestion = ask(main, "Parent question");
        run(parentQuestion, MentorTurn.Status.DONE, "Parent answer");
        MentorConversation thread = conversations.openThread(
                workspace.getId(), parentQuestion.question().getId());
        // A later summary of the main conversation, written after the thread's parent.
        MentorTurnService.Submission later = ask(main, "Later question");
        MentorTurn laterTurn = run(later, MentorTurn.Status.DONE, "Later answer");
        MentorEntry laterQuestion =
                entries.require(workspace.getId(), later.question().getId());
        entries.append(
                workspace.getId(),
                main.getId(),
                summary("Main so far", MentorLedgerFixtures.piIdOf(laterQuestion), laterTurn.getId()));
        MentorTurnService.Submission inThread = ask(thread, "Thread question");
        MentorTurn threadTurn = turns.claimNext(LEASE).orElseThrow();

        MentorContextPathBuilder.TurnContext context = paths.forTurn(workspace.getId(), threadTurn.getId(), BERLIN);

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
        run(ask(main, "Stop me"), MentorTurn.Status.STOPPED, "Half an answer");
        run(ask(main, "Fail me"), MentorTurn.Status.FAILED, "Lost answer");

        List<String> path = texts(mainPath());

        assertThat(path).hasSize(3);
        assertThat(path.get(1)).isEqualTo("Half an answer" + MentorContextPathBuilder.STOPPED_MARKER);
        assertThat(path.get(2))
                .endsWith(" Fail me\n\n" + MentorContextPathBuilder.FAILED_MARKER)
                .doesNotContain("Lost answer");
    }

    @Test
    void shouldMarkTheQuestionWhenTheDeveloperStoppedATurnBeforeItRan() {
        MentorTurnService.Submission submission = ask(main, "Never mind");
        turns.stop(workspace.getId(), submission.turn().getId());

        List<String> path = texts(mainPath());

        assertThat(path)
                .singleElement()
                .asString()
                .endsWith(" Never mind\n\n" + MentorContextPathBuilder.STOPPED_MARKER);
    }

    @Test
    void shouldOmitADeletedQuestionButKeepAnErasedToolResultAsASkeletonWhenEntriesAreRedacted() {
        MentorTurnService.Submission submission = ask(main, "Secret question");
        MentorTurn turn = turns.claimNext(LEASE).orElseThrow();
        turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                new MentorTurnService.Ending(
                        MentorTurn.Status.DONE,
                        List.of(
                                MentorLedgerFixtures.toolCall(
                                        MentorLedgerFixtures.piId(),
                                        "call-1",
                                        "fetch_context",
                                        JsonNodeFactory.instance.objectNode().put("path", "user.json")),
                                MentorLedgerFixtures.toolResult(
                                        MentorLedgerFixtures.piId(),
                                        "call-1",
                                        "fetch_context",
                                        "colleague data",
                                        false)),
                        null));
        MentorEntry toolResult = entryRepository
                .findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(
                        workspace.getId(), turn.getId(), MentorEntry.Kind.TOOL_RESULT)
                .getFirst();

        redaction.redact(List.of(toolResult.getId()));
        entries.deleteMessage(workspace.getId(), submission.question().getId());

        List<ObjectNode> path = mainPath();
        assertThat(path).hasSize(2);
        assertThat(path.toString()).doesNotContain("Secret question", "colleague data");
        assertThat(path.get(1).path("message").path("toolCallId").asString()).isEqualTo("call-1");
        assertThat(text(path.get(1))).isEqualTo(MentorRedaction.ERASED);
    }

    private static MentorEntryDraft summary(String text, String firstKeptEntryId, UUID turnId) {
        return new MentorEntryDraft(
                MentorEntry.Kind.SUMMARY,
                MentorEntry.Surface.APP,
                text,
                MentorLedgerFixtures.compaction(MentorLedgerFixtures.piId(), text, firstKeptEntryId),
                turnId,
                null,
                null);
    }
}
