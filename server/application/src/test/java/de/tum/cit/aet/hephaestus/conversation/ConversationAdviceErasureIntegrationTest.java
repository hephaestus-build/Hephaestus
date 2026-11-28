package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.event.ScmMirrorErasedEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.advice.AdviceRedaction;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceErasureListener;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceRevisionTexts;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceStaleCause;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.PracticeModelTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.EntityManager;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * #5278: advice that loses its text to an erasure or a withdrawal takes back every answer whose basis read it, and what
 * derives from those, in the deleting transaction ({@code ConversationAdviceErasure}). One test per row of the advice
 * cases (A1 to A11, S3a, S5). Each enters through the real {@link AdviceRedaction}, which every deleting path calls.
 */
class ConversationAdviceErasureIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final AtomicLong SOURCE = new AtomicLong(7_700_000_000L);

    @Autowired
    private PracticeModelTestFixtures modelFixtures;

    @Autowired
    private ConversationTurnClaims claims;

    @Autowired
    private ConversationService conversations;

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
    private AdviceRedaction advice;

    @Autowired
    private AdviceErasureListener listener;

    @Autowired
    private AdviceRevisionTexts adviceTexts;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private ConversationFeedbackErasure slackThreads;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private Workspace workspace;
    private long ws;
    private long owner;
    private Conversation main;
    private UUID adviceId;

    @BeforeEach
    void setUp() {
        User developer = persistUser("advice-reader");
        workspace = createWorkspace("advice-erasure", "Hephaestus", "advice-erasure-org", AccountType.ORG, developer);
        ws = workspace.getId();
        owner = accountId(developer);
        main = conversations.findOrCreateMain(ws, owner);
        adviceId = UUID.randomUUID();
        long source = SOURCE.incrementAndGet();
        var work = modelFixtures.work(workspace, "advice-" + source, "scm.pull_request", source);
        jdbc.update("""
                INSERT INTO practice_advice (id, workspace_id, person_account_id, practice_id, state, version, created_at)
                VALUES (?, ?, ?, ?, 'CURRENT', 0, now())
                """, adviceId, ws, owner, work.practice().getId());
    }

    /** A1: a person's erasure of the evidence erases the answer whose basis read the advice. */
    @Test
    void shouldEraseTheAnswerThatReadAdviceWhenItsEvidenceIsErased() {
        Result evidence = observed();
        UUID revision = revision(1, evidence);
        Answered read = answerReading(revision, "Name the rollback, as the advice says.");

        delete(evidence, AdviceStaleCause.ERASURE);

        assertThat(redacted(read.answer())).containsExactly(true);
        assertThat(redacted(read.question())).containsExactly(false);
        assertThat(revisionText(revision)).isNull();
    }

    /** A2: an answer that read only that answer goes too. */
    @Test
    void shouldEraseALaterAnswerThatReadOnlyTheAnswerThatReadAdvice() {
        Result evidence = observed();
        UUID revision = revision(1, evidence);
        Answered read = answerReading(revision, "Name the rollback.");
        Answered later = answer("What did you say?", "I said to name the rollback.");
        assertThat(sources(later.turn())).contains(read.answer());

        delete(evidence, AdviceStaleCause.ERASURE);

        assertThat(redacted(read.answer(), later.answer())).containsOnly(true);
        assertThat(redacted(later.question())).containsExactly(false);
    }

    /** A3: the walk passes through an answer that an earlier, non-transitive redaction erased. */
    @Test
    void shouldEraseALaterAnswerThroughAnAnswerThatAnEarlierRedactionErased() {
        Result evidence = observed();
        UUID revision = revision(1, evidence);
        Answered read = answerReading(revision, "Name the rollback.");
        Answered later = answer("What did you say?", "I said to name the rollback.");
        redaction.redact(List.of(read.answer()));
        assertThat(redacted(read.answer(), later.answer())).containsExactly(true, false);

        delete(evidence, AdviceStaleCause.ERASURE);

        assertThat(redacted(later.answer())).containsExactly(true);
    }

    /** A4: an answer that read another revision of the workspace keeps its words. */
    @Test
    void shouldKeepAnAnswerThatReadAnUnrelatedRevision() {
        Result evidence = observed();
        Result otherEvidence = observed();
        UUID revision = revision(1, evidence);
        UUID unrelated = revision(2, otherEvidence);
        // The other answer comes first: an answer after the erased one reads it, and goes with it.
        Answered other = answerReading(unrelated, "Split the change.");
        Answered read = answerReading(revision, "Name the rollback.");

        delete(evidence, AdviceStaleCause.ERASURE);

        assertThat(redacted(read.answer(), other.answer())).containsExactly(true, false);
        assertThat(revisionText(unrelated)).isNotNull();
    }

    @Test
    void shouldKeepAnOlderThreadAnswerWhenTheAdviceReaderAlsoUsedACopyingTool() {
        Result evidence = observed();
        UUID revision = revision(1, evidence);
        Answered parent = answer("What is two plus two?", "Four.");
        Conversation thread = conversations.openThread(ws, parent.answer());
        ConversationTurn older = claim(turns.submit(
                ws,
                thread.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP,
                        "And three plus three?",
                        UUID.randomUUID().toString(),
                        null,
                        null)));
        UUID unrelated = end(older, context(older).prompt(), "Six.");

        ConversationTurn reader = claim(ask("What should I change?"));
        String prompt = read(reader, revision);
        UUID affected = end(
                reader,
                prompt,
                "Name the rollback.",
                List.of(
                        ConversationLedgerFixtures.toolCall(
                                ConversationLedgerFixtures.piId(),
                                "context-1",
                                "fetch_context",
                                JsonNodeFactory.instance.objectNode().put("path", "team.json")),
                        ConversationLedgerFixtures.toolResult(
                                ConversationLedgerFixtures.piId(),
                                "context-1",
                                "fetch_context",
                                "Team notes.",
                                false)));
        assertThat(sources(older.getId())).doesNotContain(affected);
        assertThat(redacted(affected, unrelated)).containsExactly(false, false);

        delete(evidence, AdviceStaleCause.ERASURE);

        assertThat(redacted(affected, unrelated)).containsExactly(true, false);
    }

    /** A5: the walk stays in the workspace that the deletion names. */
    @Test
    void shouldKeepTheAnswerWhenTheRevisionIdComesWithAnotherWorkspace() {
        UUID revision = revision(1, observed());
        Answered read = answerReading(revision, "Name the rollback.");
        long otherWorkspace = createWorkspace(
                        "advice-other", "Other", "advice-other-org", AccountType.ORG, persistUser("advice-other"))
                .getId();

        transactions.executeWithoutResult(
                status -> listener.staleRevisions(otherWorkspace, List.of(revision), AdviceStaleCause.ERASURE));

        assertThat(redacted(read.answer())).containsExactly(false);
    }

    /** A6: a source withdrawal (an SCM mirror erasure, through DerivedResultEraser) erases the answer. */
    @Test
    void shouldEraseTheAnswerThatReadAdviceWhenItsEvidenceIsWithdrawn() {
        Result evidence = observed();
        UUID revision = revision(1, evidence);
        Answered read = answerReading(revision, "Name the rollback.");

        transactions.executeWithoutResult(status -> events.publishEvent(new ScmMirrorErasedEvent(ws)));

        assertThat(revisionText(revision)).isNull();
        assertThat(redacted(read.answer())).containsExactly(true);
    }

    /** A7: a retention takes the advice text and keeps the answer, which has its own retention. */
    @Test
    void shouldKeepTheAnswerWhenTheEvidenceReachesItsRetention() {
        Result evidence = observed();
        UUID revision = revision(1, evidence);
        Answered read = answerReading(revision, "Name the rollback.");

        delete(evidence, AdviceStaleCause.RETENTION);

        assertThat(revisionText(revision)).isNull();
        assertThat(redacted(read.answer())).containsExactly(false);
    }

    /** A8: when the erasure of the answers cannot finish, the deletion rolls back and keeps everything for its retry. */
    @Test
    void shouldRollTheDeletionBackWhenTheAnswersCannotBeErased() throws Exception {
        Result evidence = observed();
        UUID revision = revision(1, evidence);
        Answered read = answerReading(revision, "Name the rollback.");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM conversation WHERE id = ? FOR UPDATE", main.getId());
                locked.countDown();
                await(release);
            }));
            await(locked);

            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                        jdbc.execute("SET LOCAL lock_timeout = '300ms'");
                        advice.beforeResultsDeleted(ws, List.of(evidence.getId()), AdviceStaleCause.ERASURE);
                    }))
                    .isInstanceOf(RuntimeException.class);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertThat(revisionText(revision)).isNotNull();
        assertThat(redacted(read.answer())).containsExactly(false);
    }

    /** A10: a binding goes with its revision, which the owner's erasure and a workspace purge delete. */
    @Test
    void shouldDropTheBindingWithItsRevision() {
        UUID revision = revision(1, observed());
        answerReading(revision, "Name the rollback.");
        assertThat(bindings(revision)).isOne();

        jdbc.update("DELETE FROM practice_advice WHERE id = ?", adviceId);

        assertThat(bindings(revision)).isZero();
    }

    /** A11: an attempt that read the advice and ends after its evidence was erased stores none of its words. */
    @Test
    void shouldStoreNoWordsOfAnAttemptThatReadAdviceErasedWhileItRan() {
        Result evidence = observed();
        UUID revision = revision(1, evidence);
        ConversationTurn running = claim(ask("What should I change?"));
        String prompt = read(running, revision);

        delete(evidence, AdviceStaleCause.ERASURE);
        UUID stored = end(running, prompt, "Name the rollback.");

        assertThat(redacted(stored)).containsExactly(true);
    }

    /** S3a: a Slack channel erasure withdraws the results of its threads, and the answer that read their advice goes. */
    @Test
    void shouldEraseTheAnswerThatReadAdviceWhenItsSlackThreadIsErased() {
        long thread = SOURCE.incrementAndGet();
        UUID revision = revision(1, observed("chat.thread", thread));
        Answered read = answerReading(revision, "Name the rollback.");

        slackThreads.eraseForThreads(ws, List.of(thread));

        assertThat(revisionText(revision)).isNull();
        assertThat(redacted(read.answer())).containsExactly(true);
    }

    /** S5: the Slack retention of a thread takes the advice text as a retention and keeps the answer. */
    @Test
    void shouldKeepTheAnswerThatReadAdviceWhenItsSlackThreadReachesItsRetention() {
        long thread = SOURCE.incrementAndGet();
        UUID revision = revision(1, observed("chat.thread", thread));
        Answered read = answerReading(revision, "Name the rollback.");

        slackThreads.expireForThreads(ws, List.of(thread));

        assertThat(revisionText(revision)).isNull();
        assertThat(redacted(read.answer())).containsExactly(false);
    }

    private record Answered(UUID turn, UUID question, UUID answer) {}

    /** A current observation of the owner on new work of this kind. */
    private Result observed(String kind, long source) {
        var work = modelFixtures.work(workspace, "advice-" + source, kind, source);
        return modelFixtures.result(work, owner, "Name the rollback");
    }

    private Result observed() {
        return observed("scm.pull_request", SOURCE.incrementAndGet());
    }

    /** A revision of the advice with this result as its one cited source. */
    private UUID revision(int number, Result source) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO practice_advice_revision
                    (id, workspace_id, person_account_id, advice_id, number, text, review_id, cited_count, source_count,
                     written_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, now())
                """, id, ws, owner, adviceId, number, "Name the rollback in the description.", source.getReviewId());
        jdbc.update("""
                INSERT INTO practice_advice_source (id, workspace_id, person_account_id, revision_id, result_id, cited,
                                                    ordinal)
                VALUES (?, ?, ?, ?, ?, true, 0)
                """, UUID.randomUUID(), ws, owner, id, source.getId());
        return id;
    }

    /** The redaction that every deleting transaction runs before it deletes the results. */
    private void delete(Result evidence, AdviceStaleCause cause) {
        transactions.executeWithoutResult(status -> advice.beforeResultsDeleted(ws, List.of(evidence.getId()), cause));
    }

    /** One turn that reads its context and this advice revision, and stores one answer. */
    private Answered answerReading(UUID revision, String answer) {
        ConversationTurnService.Submission asked = ask("What should I change?");
        ConversationTurn turn = claim(asked);
        String prompt = read(turn, revision);
        return new Answered(turn.getId(), asked.question().getId(), end(turn, prompt, answer));
    }

    /** One turn that reads its context and stores one answer. */
    private Answered answer(String question, String answer) {
        ConversationTurnService.Submission asked = ask(question);
        ConversationTurn turn = claim(asked);
        String prompt = context(turn).prompt();
        return new Answered(turn.getId(), asked.question().getId(), end(turn, prompt, answer));
    }

    /** The context, then the advice read's binding, with the locks that {@link ConversationBasis#bindAdvice} names. */
    private String read(ConversationTurn turn, UUID revision) {
        String prompt = context(turn).prompt();
        transactions.executeWithoutResult(status -> {
            List<UUID> locked = adviceTexts.lockWithText(ws, List.of(revision));
            jdbc.queryForList("SELECT id FROM conversation WHERE id = ? FOR SHARE", main.getId());
            basis.bindAdvice(ws, attemptBasis(turn.getId()), locked);
        });
        return prompt;
    }

    private ConversationTurnService.Submission ask(String text) {
        return turns.submit(
                ws,
                main.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
    }

    private ConversationTurn claim(ConversationTurnService.Submission submission) {
        return claims.claim(submission.turn());
    }

    private ConversationContextPathBuilder.TurnContext context(ConversationTurn turn) {
        return paths.forTurn(ws, turn.getId(), BERLIN);
    }

    /** Ends the turn with its question's Pi entry and one answer, and returns the answer's entry id. */
    private UUID end(ConversationTurn turn, String prompt, String answer) {
        return end(turn, prompt, answer, List.of());
    }

    private UUID end(ConversationTurn turn, String prompt, String answer, List<JsonNode> toolEntries) {
        List<JsonNode> appended = new ArrayList<>();
        appended.add(ConversationLedgerFixtures.user(ConversationLedgerFixtures.piId(), prompt));
        appended.addAll(toolEntries);
        appended.add(ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), answer));
        turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new ConversationTurnService.Ending(ConversationTurn.Status.DONE, appended, null, null));
        return ConversationLedgerFixtures.turnEntries(entityManager, ws, turn.getId()).stream()
                .filter(entry -> entry.getKind() == ConversationEntry.Kind.ANSWER)
                .map(ConversationEntry::getId)
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private UUID attemptBasis(UUID turn) {
        return Objects.requireNonNull(
                turnRepository.findById(turn).orElseThrow().getBasisId());
    }

    private List<UUID> sources(UUID turn) {
        return jdbc
                .queryForList(
                        "SELECT source_id FROM basis_edge WHERE workspace_id = ? AND basis_id = ?",
                        UUID.class,
                        ws,
                        attemptBasis(turn))
                .stream()
                .map(Objects::requireNonNull)
                .toList();
    }

    private long bindings(UUID revision) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM basis_advice_edge WHERE revision_id = ?", Long.class, revision));
    }

    private @Nullable String revisionText(UUID revision) {
        return jdbc.queryForObject("SELECT text FROM practice_advice_revision WHERE id = ?", String.class, revision);
    }

    private List<Boolean> redacted(UUID... ids) {
        List<Boolean> redacted = new ArrayList<>();
        for (UUID id : ids) {
            redacted.add(entryRepository.findById(id).orElseThrow().isRedacted());
        }
        return redacted;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
