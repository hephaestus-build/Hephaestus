package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.agent.mentor.MentorLlmConfig;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerFixtures;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnUsage;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnWaits;
import de.tum.cit.aet.hephaestus.practices.feedback.ConversationFeedbackRows;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStore;
import de.tum.cit.aet.hephaestus.testconfig.ConversationReceipts;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Ending an attempt: billing it to the usage ledger when it ends and when its lease expires, and settling the
 * conversation copies it claimed by what its answer showed.
 */
class MentorTurnCompletionIntegrationTest extends AbstractWorkspaceIntegrationTest {

    /** $10 per million input tokens; nothing else is priced. */
    private static final MentorLlmConfig ADMITTED = LlmCatalogTestFixtures.admittedMentorConfig();

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTurnWaits waits;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private MentorTurnCompletion completion;

    @Autowired
    private MentorAttemptUsageService attemptUsage;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private FeedbackStore feedback;

    @Autowired
    private FailedEventPublications failedPublications;

    @Autowired
    private IncompleteEventPublications incompletePublications;

    private Workspace workspace;
    private long account;
    private MentorConversation main;

    @BeforeEach
    void setUpLedger() {
        User developer = persistUser("heph-billing-developer");
        workspace = createWorkspace("heph-billing", "Hephaestus", "heph-org", AccountType.ORG, developer);
        account = accountId(developer);
        main = conversations.findOrCreateMain(workspace.getId(), account);
    }

    /** Feedback the attempt claimed through {@code show_feedback}. */
    private UUID claimed(MentorTurn turn) {
        return ConversationFeedbackRows.claimedBy(jdbc, workspace.getId(), account, turn.getId(), "Pin images.");
    }

    /** Hephaestus's message that shows the feedback, the call's successful result, and its closing words. */
    private static List<JsonNode> showing(UUID feedbackId, String messageId) {
        return List.of(
                MentorLedgerFixtures.showFeedback(
                        messageId, "Here is what I saw.", Map.of("call-1", feedbackId), "Pin."),
                MentorLedgerFixtures.toolResult(MentorLedgerFixtures.piId(), "call-1", "show_feedback", "Shown", false),
                MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "Anything else?"));
    }

    private void end(MentorTurn turn, MentorTurn.Status outcome, List<JsonNode> entries) {
        completion.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                new MentorTurnCompletion.Ending(
                        outcome, entries, outcome == MentorTurn.Status.FAILED ? "x" : null, null, null, null));
    }

    /** The feedback's state, its copy's state, whether a receipt settled the copy, and the answer it names. */
    private String settled(UUID feedbackId) {
        ConversationReceipts.awaitSettled(jdbc);
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT f.state||'/'||c.state||'/'||(c.posted_at IS NOT NULL OR c.last_error IS NOT NULL)||'/'"
                        + "||COALESCE((SELECT e.payload->>'id' FROM mentor_entry e WHERE e.id=c.mentor_entry_id),'-')"
                        + " FROM feedback f JOIN feedback_copy c ON c.feedback_id=f.id WHERE f.id=?",
                String.class,
                feedbackId));
    }

    /**
     * The database refuses the receipt of this feedback, as a database that is briefly away does, until
     * {@link #databaseBack} ends the outage.
     */
    private void databaseAway(UUID feedbackId) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_receipt_outage(feedback_id uuid PRIMARY KEY)");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_receipt_outage() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                 IF EXISTS(SELECT 1 FROM test_receipt_outage WHERE feedback_id=NEW.feedback_id) THEN
                  RAISE EXCEPTION 'database away' USING ERRCODE='08006';
                 END IF;
                 RETURN NEW;
                END $$""");
        jdbc.execute("CREATE OR REPLACE TRIGGER test_receipt_outage BEFORE UPDATE ON feedback_copy"
                + " FOR EACH ROW EXECUTE FUNCTION test_receipt_outage()");
        jdbc.update("INSERT INTO test_receipt_outage(feedback_id) VALUES (?)", feedbackId);
    }

    private void databaseBack(UUID feedbackId) {
        jdbc.update("DELETE FROM test_receipt_outage WHERE feedback_id=?", feedbackId);
    }

    /** The outage is schema; schema left behind leaks into every later class that reads the catalog. */
    @AfterEach
    void removeOutage() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_receipt_outage ON feedback_copy");
        jdbc.execute("DROP FUNCTION IF EXISTS test_receipt_outage()");
        jdbc.execute("DROP TABLE IF EXISTS test_receipt_outage");
    }

    @Test
    void shouldSettleTheReceiptWhenItsFailedPublicationIsResubmittedAfterATransientFailure() {
        MentorTurn turn = admittedTurn(Duration.ofMinutes(5));
        UUID shown = claimed(turn);
        databaseAway(shown);

        end(turn, MentorTurn.Status.DONE, showing(shown, "m7a1b2c3"));

        assertThat(settled(shown)).as("the receipt failed").isEqualTo("UNCONFIRMED/WRITE_STARTED/false/-");
        assertThat(ConversationReceipts.failed(jdbc)).isPositive();
        databaseBack(shown);

        failedPublications.resubmit(ResubmissionOptions.defaults().withMinAge(Duration.ZERO));

        assertThat(settled(shown)).isEqualTo("DELIVERED/POSTED/true/m7a1b2c3");
    }

    @Test
    void shouldSettleTheReceiptAfterARestartWhenTheProcessDiedAfterTheOutcomeCommitted() {
        MentorTurn turn = admittedTurn(Duration.ofMinutes(5));
        UUID shown = claimed(turn);
        // The process dies after the end committed and before the receipt was stored.
        databaseAway(shown);
        end(turn, MentorTurn.Status.DONE, showing(shown, "m8a1b2c3"));
        assertThat(settled(shown)).isEqualTo("UNCONFIRMED/WRITE_STARTED/false/-");
        databaseBack(shown);

        // A restart runs every publication that is not complete.
        incompletePublications.resubmitIncompletePublications(publication -> true);

        assertThat(settled(shown)).isEqualTo("DELIVERED/POSTED/true/m8a1b2c3");
    }

    @Test
    void shouldMakeNoReceiptDueWhenTheEndRollsBack() {
        MentorTurn turn = admittedTurn(Duration.ofMinutes(5));
        UUID shown = claimed(turn);

        transactionTemplate.executeWithoutResult(status -> {
            end(turn, MentorTurn.Status.DONE, showing(shown, "m9a1b2c3"));
            status.setRollbackOnly();
        });

        assertThat(settled(shown)).isEqualTo("UNCONFIRMED/WRITE_STARTED/false/-");
        assertThat(turnRepository.findById(turn.getId()).orElseThrow().getStatus())
                .isEqualTo(MentorTurn.Status.RUNNING);
    }

    @Test
    void shouldSettleTheClaimsOfAnAttemptTheLedgerTookBackUnconfirmed() {
        MentorTurn turn = admittedTurn(Duration.ofMinutes(5));
        UUID called = claimed(turn);
        MentorTurn running = turnRepository.findById(turn.getId()).orElseThrow();
        MentorTurnService.LostAttempt lost = transactionTemplate.execute(
                status -> turns.requeueRunning(workspace.getId(), running.getConversationId())
                        .orElseThrow());

        assertThat(Objects.requireNonNull(lost).attempt()).isEqualTo(running.getAttempt());
        assertThat(settled(called))
                .as("its host may have shown the words, so they are never shown again")
                .isEqualTo("UNCONFIRMED/WRITE_STARTED/true/-");
    }

    @Test
    void shouldDeliverTheFeedbackOnTheMessageThatShowedItWhenAnAppAnswerEndsDone() {
        MentorTurn turn = admittedTurn(Duration.ofMinutes(5));
        UUID shown = claimed(turn);
        UUID notShown = claimed(turn);

        end(turn, MentorTurn.Status.DONE, showing(shown, "m0a1b2c3"));

        assertThat(settled(shown)).isEqualTo("DELIVERED/POSTED/true/m0a1b2c3");
        assertThat(settled(notShown)).as("the answer never showed it").isEqualTo("FAILED/FAILED/true/-");
    }

    @Test
    void shouldLeaveTheFeedbackUnconfirmedWhenTheAttemptFailsAfterItsCallAndFailWhatItNeverShowed() {
        MentorTurn turn = admittedTurn(Duration.ofMinutes(5));
        UUID called = claimed(turn);
        UUID neverCalled = claimed(turn);

        end(turn, MentorTurn.Status.FAILED, showing(called, "m1a1b2c3"));

        assertThat(settled(called)).isEqualTo("UNCONFIRMED/WRITE_STARTED/true/-");
        assertThat(settled(neverCalled)).isEqualTo("FAILED/FAILED/true/-");
    }

    @Test
    void shouldLeaveTheFeedbackUnconfirmedWhenTheDeveloperStopsTheAnswerThatShowedIt() {
        MentorTurn turn = admittedTurn(Duration.ofMinutes(5));
        UUID called = claimed(turn);
        turns.stop(workspace.getId(), turn.getId());

        end(turn, MentorTurn.Status.STOPPED, showing(called, "m2a1b2c3"));

        assertThat(settled(called)).isEqualTo("UNCONFIRMED/WRITE_STARTED/true/-");
    }

    @Test
    void shouldSettleALostAttemptsClaimUnconfirmedSoItsRetryCannotClaimItAgain() {
        MentorTurnLeaseReaper reaper = new MentorTurnLeaseReaper(turns, completion, metrics, transactions, waits);
        MentorTurn first = admittedTurn(Duration.ofSeconds(-1));
        UUID called = claimed(first);

        reaper.reap();

        assertThat(settled(called)).isEqualTo("UNCONFIRMED/WRITE_STARTED/true/-");
        MentorTurn second = turns.claimNext(Duration.ofMinutes(5)).orElseThrow();
        assertThat(second.getAttempt()).isEqualTo(2);
        assertThat(feedback.claimConversationCopy(workspace.getId(), called, second.getId(), second.getAttempt()))
                .isEmpty();
        end(second, MentorTurn.Status.DONE, showing(called, "m3a1b2c3"));
        assertThat(settled(called))
                .as("the retry's end settles none of the lost attempt's claims")
                .isEqualTo("UNCONFIRMED/WRITE_STARTED/true/-");
    }

    /** A claimed attempt admitted at {@link #ADMITTED}'s rates. */
    private MentorTurn admittedTurn(Duration lease) {
        turns.submit(
                workspace.getId(),
                main.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP, "Question", UUID.randomUUID().toString(), null, null));
        MentorTurn turn = turns.claimNext(Duration.ofMinutes(5)).orElseThrow();
        admit(turn);
        if (lease.isNegative()) expire(turn);
        return turn;
    }

    private void expire(MentorTurn turn) {
        turns.renewLease(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                Duration.ofSeconds(-1));
    }

    /** Admits the attempt as the worker does, which also creates its usage measurement. */
    private void admit(MentorTurn turn) {
        assertThat(attemptUsage.admit(
                        turn.getWorkspaceId(),
                        turn.getId(),
                        turn.getAttempt(),
                        Objects.requireNonNull(turn.getLeaseId()),
                        MentorTurnAdmission.of(ADMITTED, Objects.requireNonNull(ADMITTED.priceSnapshot()))))
                .isTrue();
    }

    /** One proxied call, settled on its attempt as the proxy's accumulator settles it. */
    private void proxyCall(MentorTurn turn, long input) {
        transactionTemplate.executeWithoutResult(tx ->
                attemptUsage.settleCall(turn.getId(), turn.getAttempt(), new MentorTurnUsage(input, 10, 0, 0, 0, 1)));
    }

    /** One row of the usage ledger, as billed. */
    private record Billed(int attempt, long inputTokens, int calls, String provenance, BigDecimal costUsd) {}

    private List<Billed> ledger(MentorTurn turn) {
        return jdbc.query(
                "SELECT source_attempt, input_tokens, total_calls, usage_provenance, cost_usd FROM llm_usage_event "
                        + "WHERE source_id = ? ORDER BY source_attempt",
                (rs, row) -> new Billed(
                        rs.getInt(1),
                        rs.getLong(2),
                        rs.getInt(3),
                        Objects.requireNonNull(rs.getString(4)),
                        Objects.requireNonNull(rs.getBigDecimal(5))),
                turn.getId());
    }

    @Test
    void shouldKeepTheCostOfTheLaterAttemptWhenATakenBackAttemptIsBilledAfterItEnded() {
        MentorTurn first = admittedTurn(Duration.ofMinutes(5));
        proxyCall(first, 30_000);
        MentorTurnService.LostAttempt lost = Objects.requireNonNull(
                transactionTemplate.execute(status -> turns.requeueRunning(workspace.getId(), first.getConversationId())
                        .orElseThrow()));
        ConversationReceipts.awaitSettled(jdbc);
        MentorTurn second = turns.claimNext(Duration.ofMinutes(5)).orElseThrow();
        admit(second);
        proxyCall(second, 70_000);
        end(second, MentorTurn.Status.DONE, List.of());

        // The billing of the taken-back attempt runs again after the turn's next attempt ended.
        completion.billTakenBack(new MentorLedgerEvents.AttemptTakenBack(
                workspace.getId(),
                first.getId(),
                lost.attempt(),
                lost.admission(),
                lost.proxyUsage(),
                lost.startedAt(),
                lost.lostAt()));

        MentorTurn row = turnRepository.findById(first.getId()).orElseThrow();
        assertThat(row.getAttempt()).isEqualTo(2);
        assertThat(row.getInputTokens()).isEqualTo(70_000);
        assertThat(row.getCostUsd()).as("the cost of the attempt the row holds").isEqualByComparingTo("0.70");
        assertThat(ledger(first))
                .extracting(Billed::attempt, Billed::inputTokens)
                .containsExactly(tuple(1, 30_000L), tuple(2, 70_000L));
    }

    @Test
    void shouldBillTheProxysRecordAndRecordItOnTheTurnWhenTheAttemptEnds() {
        MentorTurn turn = admittedTurn(Duration.ofMinutes(5));
        proxyCall(turn, 50_000);
        proxyCall(turn, 50_000);

        completion.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                new MentorTurnCompletion.Ending(MentorTurn.Status.DONE, List.of(), null, null, null, null));

        assertThat(ledger(turn)).singleElement().satisfies(row -> {
            assertThat(row.attempt()).isEqualTo(1);
            assertThat(row.inputTokens())
                    .as("the sum of the proxy's calls, the only view of them")
                    .isEqualTo(100_000);
            assertThat(row.provenance()).isEqualTo("PROXY");
            assertThat(row.calls()).isEqualTo(2);
            assertThat(row.costUsd()).isEqualByComparingTo("1.00");
        });
        MentorTurn row = turnRepository.findById(turn.getId()).orElseThrow();
        assertThat(row.getInputTokens()).isEqualTo(100_000);
        assertThat(row.getCostUsd()).isEqualByComparingTo("1.00");
    }

    @Test
    void shouldBillNothingWhenTheAttemptMadeNoCall() {
        MentorTurn turn = admittedTurn(Duration.ofMinutes(5));

        completion.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                new MentorTurnCompletion.Ending(MentorTurn.Status.FAILED, List.of(), "broken", null, null, null));

        assertThat(ledger(turn)).isEmpty();
    }

    @Test
    void shouldRecordWhyTheAttemptFailedOnTheTurnWhenItEndsFailed() {
        MentorTurn turn = admittedTurn(Duration.ofMinutes(5));
        proxyCall(turn, 50_000);

        completion.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                new MentorTurnCompletion.Ending(
                        MentorTurn.Status.FAILED,
                        List.of(),
                        "Hephaestus could not finish this reply.",
                        "its conversation loop reported ERROR: provider_unavailable",
                        new MentorTurn.FailedCall("upstream_status", 503),
                        null));

        MentorTurn row = turnRepository.findById(turn.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(row.getFailureCause()).isEqualTo("its conversation loop reported ERROR: provider_unavailable");
        assertThat(row.getFailedCall()).isEqualTo("upstream_status");
        assertThat(row.getFailedCallStatus()).isEqualTo(503);
        assertThat(row.getLlmCalls())
                .as("the model calls that completed before it failed")
                .isEqualTo(1);
    }

    @Test
    void shouldCutACauseAndACallKindToTheirColumnsAndStillEndTheTurn() {
        MentorTurn turn = admittedTurn(Duration.ofMinutes(5));

        completion.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                new MentorTurnCompletion.Ending(
                        MentorTurn.Status.FAILED,
                        List.of(),
                        "Hephaestus could not finish this reply.",
                        "c".repeat(300),
                        new MentorTurn.FailedCall("k".repeat(100), null),
                        null));

        MentorTurn row = turnRepository.findById(turn.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(row.getFailureCause()).isEqualTo("c".repeat(200));
        assertThat(row.getFailedCall()).isEqualTo("k".repeat(64));
    }

    @Test
    void shouldRecordNoFailureOnTheTurnWhenItEndsDone() {
        MentorTurn turn = admittedTurn(Duration.ofMinutes(5));

        completion.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                new MentorTurnCompletion.Ending(
                        MentorTurn.Status.DONE,
                        List.of(),
                        null,
                        null,
                        new MentorTurn.FailedCall("upstream_idle", 200),
                        null));

        MentorTurn row = turnRepository.findById(turn.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(MentorTurn.Status.DONE);
        assertThat(row.getFailureCause()).isNull();
        assertThat(row.getFailedCall())
                .as("a retried call that failed does not fail the turn")
                .isNull();
        assertThat(row.getFailedCallStatus()).isNull();
    }

    @Test
    void shouldBillEachLostAttemptFromTheProxyWhenTheReaperTakesItsLeaseBack() {
        // Built directly: the bean's scheduler lock would skip a second sweep within its minimum hold.
        MentorTurnLeaseReaper reaper = new MentorTurnLeaseReaper(turns, completion, metrics, transactions, waits);
        MentorTurn first = admittedTurn(Duration.ofSeconds(-1));
        jdbc.update("UPDATE mentor_turn SET queued_at = now() - interval '10 minutes' WHERE id = ?", first.getId());
        Instant queuedBeforeLoss =
                turnRepository.findById(first.getId()).orElseThrow().getQueuedAt();
        proxyCall(first, 30_000);

        reaper.reap();

        MentorTurn requeued = turnRepository.findById(first.getId()).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(requeued.getInputTokens()).isZero();
        assertThat(requeued.getQueuedAt()).isAfter(queuedBeforeLoss);
        MentorTurn second = turns.claimNext(Duration.ofMinutes(5)).orElseThrow();
        admit(second);
        expire(second);
        proxyCall(second, 70_000);

        reaper.reap();

        assertThat(turnRepository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(MentorTurn.Status.FAILED);
        assertThat(ledger(first))
                .extracting(Billed::attempt, Billed::inputTokens)
                .containsExactly(tuple(1, 30_000L), tuple(2, 70_000L));
    }
}
