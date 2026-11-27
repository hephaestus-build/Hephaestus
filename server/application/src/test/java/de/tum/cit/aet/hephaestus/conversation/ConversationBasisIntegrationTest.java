package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The basis of a turn's attempt ({@link ConversationBasis}): what the server binds before it releases words, and what a
 * removal then reaches. Each test is one assertion of the A0 prototype that belongs to the durable basis.
 */
class ConversationBasisIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConversationBasisIntegrationTest.class);

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Autowired
    private ConversationTurnClaims claims;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private ConversationEntryRepository entryRepository;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationTurnRepository turnRepository;

    @Autowired
    private ConversationContextPathBuilder paths;

    @Autowired
    private ConversationRedaction redaction;

    @Autowired
    private ConversationBasis basis;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private Workspace workspace;
    private Conversation main;
    private long developer;
    private String marker;

    @BeforeEach
    void setUpLedger() {
        User owner = persistUser("basis-developer");
        workspace = createWorkspace("basis", "Hephaestus", "basis-org", AccountType.ORG, owner);
        developer = accountId(owner);
        main = conversations.findOrCreateMain(workspace.getId(), developer);
        // A random marker, so that no earlier test's rows can match it.
        marker = "zebra" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** A0 1: the server binds the path and the question to the attempt before the context leaves. */
    @Test
    void shouldBindThePathAndTheQuestionToTheAttemptBeforeTheContextLeaves() {
        Answered first = answer(main, "Which base image?", "Pin the image by digest.");

        ConversationTurn next = claim(ask(main, "And in CI?"));
        ConversationContextPathBuilder.TurnContext context = context(next);

        assertThat(context.entries()).hasSize(2);
        assertThat(sources(next))
                .containsExactlyInAnyOrder(first.question(), first.answer(), context.questionEntryId());
        assertThat(revisions(next)).containsOnly(0);
    }

    /** A0 2: each {@code search_conversation} hit is bound to the attempt before the hits leave. */
    @Test
    void shouldBindEachSearchHitToTheAttemptBeforeTheHitsLeave() {
        Answered opening = answer(main, "Hello", "Hello, how can I help?");
        Conversation thread = conversations.openThread(workspace.getId(), opening.answer());
        Answered sibling =
                answer(thread, "We rebase " + marker + " weekly", "Weekly rebases keep " + marker + " small.");
        ConversationTurn searching = claim(ask(main, "What did we decide?"));
        context(searching);
        Set<UUID> before = sources(searching);

        List<ConversationEntryService.SearchHit> hits = search(searching, marker);

        assertThat(hits)
                .extracting(ConversationEntryService.SearchHit::entryId)
                .containsExactlyInAnyOrder(sibling.question(), sibling.answer());
        Set<UUID> bound = new HashSet<>(sources(searching));
        bound.removeAll(before);
        assertThat(bound).containsExactlyInAnyOrder(sibling.question(), sibling.answer());
    }

    /**
     * #5256 and A0 13: a developer's delete erases every entry derived from the message, through history, through a
     * search hit and through an earlier derived answer, and nothing else. The next attempt's context and search hold
     * none of its words.
     */
    @Test
    void shouldEraseEveryEntryDerivedFromAMessageWhenTheDeveloperDeletesIt() {
        Answered opening = answer(main, "Hello", "Hello, how can I help?");
        Conversation searchingThread = conversations.openThread(workspace.getId(), opening.answer());
        Conversation quietThread = conversations.openThread(workspace.getId(), opening.question());
        Answered taken = answer(main, "My token is " + marker, "Noted the token " + marker + ".");
        Answered repeated = answer(main, "What next?", "Rotate " + marker + " soon.");
        ConversationTurn searching = claim(ask(searchingThread, "Find the token"));
        String prompt = context(searching).prompt();
        assertThat(search(searching, marker)).isNotEmpty();
        UUID found = end(searching, prompt, "It was " + marker + ".");
        Answered quiet = answer(quietThread, "Something else", "Nothing about tokens.");
        User other = persistUser("basis-other-developer");
        ensureMembership(workspace, other, Membership.WorkspaceRole.MEMBER);
        Conversation othersMain = conversations.findOrCreateMain(workspace.getId(), accountId(other));
        Answered others = answer(othersMain, "My token is " + marker, "Noted the token " + marker + ".");

        entries.deleteMessage(workspace.getId(), taken.question());

        assertThat(redacted(taken.answer(), repeated.answer(), found)).containsOnly(true);
        assertThat(redacted(opening.question(), opening.answer(), repeated.question(), quiet.answer()))
                .containsOnly(false);
        assertThat(redacted(others.question(), others.answer())).containsOnly(false);
        ConversationTurn next = claim(ask(main, "Where were we?"));
        assertThat(context(next).entries().toString()).doesNotContain(marker);
        assertThat(search(next, marker)).isEmpty();
    }

    /**
     * The walk passes through an entry that an earlier, non-transitive redaction erased: an app thread repeated a Slack
     * answer that a Slack disconnect erased later, and the delete of the app question that the Slack answer read still
     * reaches the thread's answer.
     */
    @Test
    void shouldEraseAnAnswerDerivedThroughAnAnswerThatAnEarlierRedactionErased() {
        Answered opening = answer(main, "Hello", "How can I help?");
        Conversation thread = conversations.openThread(workspace.getId(), opening.answer());
        Answered source = answer(main, "My private project is named Finch", "I understand.");
        ConversationTurn slackTurn = claim(turns.submit(
                workspace.getId(),
                main.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.SLACK,
                        "Give that project a code",
                        "slack:" + UUID.randomUUID(),
                        "9.0",
                        null)));
        String slackPrompt = context(slackTurn).prompt();
        assertThat(sources(slackTurn)).contains(source.question());
        UUID slackAnswer = end(slackTurn, slackPrompt, "The code for Finch is " + marker);
        ConversationTurn searching = claim(ask(thread, "Find the code from Slack"));
        String prompt = context(searching).prompt();
        assertThat(search(searching, marker))
                .extracting(ConversationEntryService.SearchHit::entryId)
                .containsExactly(slackAnswer);
        assertThat(sources(searching)).contains(slackAnswer).doesNotContain(source.question(), source.answer());
        UUID derived = end(searching, prompt, "Finch has code " + marker);
        entries.redactSlackEntries(workspace.getId());
        assertThat(redacted(slackAnswer, source.question(), derived)).containsExactly(true, false, false);

        entries.deleteMessage(workspace.getId(), source.question());

        assertThat(redacted(derived)).containsExactly(true);
    }

    /** An attempt that started before attempts recorded their basis can still search; what it stores stays unproven. */
    @Test
    void shouldSearchUnboundForAnAttemptStartedBeforeBasesWereRecorded() {
        Answered earlier = answer(main, "Which base image?", "Pin " + marker + " by digest.");
        ConversationTurn running = claim(ask(main, "What did we decide?"));
        jdbc.update(
                "UPDATE conversation_turn SET basis_id = NULL WHERE workspace_id = ? AND id = ?",
                workspace.getId(),
                running.getId());

        assertThat(search(running, marker))
                .extracting(ConversationEntryService.SearchHit::entryId)
                .containsExactly(earlier.answer());

        UUID answer = end(running, context(running).prompt(), "Pin by digest.");
        assertThat(entryRepository.findById(answer).orElseThrow().getBasisId()).isNull();
    }

    /** An attempt that read an entry erased while it ran keeps only skeletons and fails, also without a fence. */
    @Test
    void shouldStoreNoWordsOfAnAttemptWhenAnEntryItReadIsErasedWhileItRuns() {
        Answered earlier = answer(main, "Which base image?", "Pin " + marker + " by digest.");
        ConversationTurn running = claim(ask(main, "Why?"));
        String prompt = context(running).prompt();
        // A redaction that fences no running turn, as a Slack disconnect's.
        redaction.redact(List.of(earlier.answer()));

        UUID stored = end(running, prompt, "Because " + marker + " drifts.");

        assertThat(redacted(stored)).containsOnly(true);
        assertThat(entryRepository.findById(stored).orElseThrow().getText()).doesNotContain(marker);
        ConversationTurn ended = turnRepository.findById(running.getId()).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(ended.getFailureCause()).isEqualTo(ConversationTurn.CONTEXT_CHANGED_CAUSE);
    }

    /** A legacy entry of unknown basis stays the owner's app history: shown and exported, never read by Hephaestus. */
    @Test
    void shouldKeepAnEntryOfUnknownBasisInTheAppButOutOfTheContextAndSearch() {
        Answered legacy = answer(main, "Which base image?", "Pin " + marker + " by digest.");
        // As an attempt stored it before attempts recorded their basis.
        jdbc.update(
                "UPDATE conversation_entry SET basis_id = NULL WHERE workspace_id = ? AND turn_id = ?",
                workspace.getId(),
                legacy.turn());

        assertThat(entryRepository.isShown(workspace.getId(), legacy.answer())).isTrue();
        assertThat(entryRepository.findOwnMessages(developer))
                .anyMatch(message -> message.getText().contains(marker));
        ConversationTurn next = claim(ask(main, "And in CI?"));
        ConversationContextPathBuilder.TurnContext context = context(next);
        assertThat(context.entries()).hasSize(1);
        assertThat(context.entries().toString()).doesNotContain(marker);
        assertThat(search(next, marker)).isEmpty();
        assertThat(sources(next)).containsExactlyInAnyOrder(legacy.question(), context.questionEntryId());
    }

    /**
     * The write cost of the basis per turn: one row for each entry that the context releases, plus the question. It logs
     * the time of the binding against the time of the whole context read, the hot read it adds to.
     */
    @Test
    void shouldBindOneRowForEachReleasedEntryOfEachTurn() {
        List<String> costs = new ArrayList<>();
        for (int turn = 1; turn <= 12; turn++) {
            ConversationTurn running = claim(ask(main, "Question " + turn));
            long started = System.nanoTime();
            ConversationContextPathBuilder.TurnContext context = context(running);
            Duration read = Duration.ofNanos(System.nanoTime() - started);
            List<UUID> released = new ArrayList<>(sources(running));
            assertThat(released).hasSize(context.entries().size() + 1);
            long bindStarted = System.nanoTime();
            transactions.executeWithoutResult(status -> basis.bind(workspace.getId(), UUID.randomUUID(), released));
            Duration bind = Duration.ofNanos(System.nanoTime() - bindStarted);
            costs.add("turn=%d rows=%d context_ms=%.2f bind_ms=%.2f"
                    .formatted(turn, released.size(), read.toNanos() / 1e6, bind.toNanos() / 1e6));
            end(running, context.prompt(), "Answer " + turn);
        }
        costs.forEach(line -> log.atInfo().log("basis write cost {}", line));
    }

    private record Answered(UUID turn, UUID question, UUID answer) {}

    /** One turn that reads its context and stores one answer. */
    private Answered answer(Conversation conversation, String question, String answer) {
        ConversationTurnService.Submission asked = ask(conversation, question);
        ConversationTurn turn = claim(asked);
        String prompt = context(turn).prompt();
        return new Answered(turn.getId(), asked.question().getId(), end(turn, prompt, answer));
    }

    private ConversationTurnService.Submission ask(Conversation conversation, String text) {
        return turns.submit(
                workspace.getId(),
                conversation.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
    }

    private ConversationTurn claim(ConversationTurnService.Submission submission) {
        return claims.claim(submission.turn());
    }

    private ConversationContextPathBuilder.TurnContext context(ConversationTurn turn) {
        return paths.forTurn(workspace.getId(), turn.getId(), BERLIN);
    }

    private List<ConversationEntryService.SearchHit> search(ConversationTurn turn, String query) {
        return entries.searchForTurn(
                workspace.getId(),
                turn.getId(),
                new ConversationEntryService.SearchQuery(
                        query, ConversationEntryService.SearchScope.ALL, null, null, 20));
    }

    /** Ends the turn with its question's Pi entry and one answer, and returns the answer's entry id. */
    private UUID end(ConversationTurn turn, String prompt, String answer) {
        List<JsonNode> appended = List.of(
                ConversationLedgerFixtures.user(ConversationLedgerFixtures.piId(), prompt),
                ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), answer));
        turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new ConversationTurnService.Ending(ConversationTurn.Status.DONE, appended, null, null));
        return ConversationLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId()).stream()
                .filter(entry -> entry.getKind() == ConversationEntry.Kind.ANSWER)
                .map(ConversationEntry::getId)
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private UUID attemptBasis(ConversationTurn turn) {
        return Objects.requireNonNull(
                turnRepository.findById(turn.getId()).orElseThrow().getBasisId());
    }

    private Set<UUID> sources(ConversationTurn turn) {
        return jdbc
                .queryForList(
                        "SELECT source_id FROM basis_edge"
                                + " WHERE workspace_id = ? AND basis_id = ? AND source_kind = 'ENTRY'",
                        UUID.class,
                        workspace.getId(),
                        attemptBasis(turn))
                .stream()
                .map(Objects::requireNonNull)
                .collect(Collectors.toSet());
    }

    private List<Integer> revisions(ConversationTurn turn) {
        return jdbc
                .queryForList(
                        "SELECT source_revision FROM basis_edge WHERE workspace_id = ? AND basis_id = ?",
                        Integer.class,
                        workspace.getId(),
                        attemptBasis(turn))
                .stream()
                .map(Objects::requireNonNull)
                .toList();
    }

    private List<Boolean> redacted(UUID... ids) {
        List<Boolean> redacted = new ArrayList<>();
        for (UUID id : ids) {
            redacted.add(entryRepository.findById(id).orElseThrow().isRedacted());
        }
        return redacted;
    }
}
