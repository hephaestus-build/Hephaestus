package de.tum.cit.aet.hephaestus.agent.conversation.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.agent.conversation.ConversationLlmConfig;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunReaper;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerEvents;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnClaims;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.observability.LogCapture;
import de.tum.cit.aet.hephaestus.practices.feedback.ConversationFeedbackRows;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationClaims;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.testconfig.ConversationReceipts;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Ending an attempt: billing it to the usage ledger when it ends and when its lease expires, and settling the
 * conversation copies it claimed by what its answer showed.
 */
class ConversationTurnCompletionIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private AgentRunReaper reaper;

    @Autowired
    private ConversationTurnClaims claims;

    /** $10 per million input tokens; nothing else is priced. */
    private static final ConversationLlmConfig ADMITTED = LlmCatalogTestFixtures.admittedConversationConfig();

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationTurnRepository turnRepository;

    @Autowired
    private ConversationTurnCompletion completion;

    @Autowired
    private LlmUsageRecorder recorder;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private JdbcTemplate jdbc;

    /** A fixture changes the schema as its owner: the application role cannot. */
    private final JdbcTemplate schemaOwner = PostgreSQLTestContainer.ownerJdbc();

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ConversationClaims feedback;

    @Autowired
    private FailedEventPublications failedPublications;

    @Autowired
    private IncompleteEventPublications incompletePublications;

    private Workspace workspace;
    private long account;
    private Conversation main;

    @BeforeEach
    void setUpLedger() {
        User developer = persistUser("ask-billing-developer");
        workspace = createWorkspace("ask-billing", "Hephaestus", "ask-org", AccountType.ORG, developer);
        account = accountId(developer);
        main = conversations.findOrCreateMain(workspace.getId(), account);
    }

    @ParameterizedTest
    @CsvSource({"DONE,COMPLETED", "FAILED,FAILED", "STOPPED,CANCELLED"})
    void shouldEndTheRunWithItsTurnsOutcome(ConversationTurn.Status outcome, String reason) {
        ConversationTurn turn = admittedTurn(Duration.ofMinutes(5));
        if (outcome == ConversationTurn.Status.STOPPED) turns.stop(workspace.getId(), turn.getId());
        end(turn, outcome, List.of());
        assertThat(turnRepository.findById(turn.getId()).orElseThrow().getStatus())
                .isEqualTo(outcome);
        assertThat(jdbc.queryForObject(
                        "SELECT stop_reason FROM agent_run WHERE id=? AND workspace_id=?",
                        String.class,
                        turn.getRunId(),
                        turn.getWorkspaceId()))
                .isEqualTo(reason);
    }

    /** Feedback the attempt claimed through {@code show_feedback}. */
    private UUID claimed(ConversationTurn turn) {
        return ConversationFeedbackRows.claimedBy(jdbc, workspace.getId(), account, turn.getId(), "Pin images.");
    }

    /** Hephaestus's message that shows the feedback, the call's successful result, and its closing words. */
    private static List<JsonNode> showing(UUID feedbackId, String messageId) {
        return List.of(
                ConversationLedgerFixtures.showFeedback(
                        messageId, "Here is what I saw.", Map.of("call-1", feedbackId), "Pin."),
                ConversationLedgerFixtures.toolResult(
                        ConversationLedgerFixtures.piId(), "call-1", "show_feedback", "Shown", false),
                ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), "Anything else?"));
    }

    private void end(ConversationTurn turn, ConversationTurn.Status outcome, List<JsonNode> entries) {
        completion.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new ConversationTurnCompletion.Ending(
                        outcome, entries, outcome == ConversationTurn.Status.FAILED ? "x" : null, null, null, null));
    }

    /** The feedback's state, its copy's state, whether a receipt settled the copy, and the answer it names. */
    private String settled(UUID feedbackId) {
        ConversationReceipts.awaitSettled(jdbc);
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT f.state||'/'||c.state||'/'||(c.posted_at IS NOT NULL OR c.last_error IS NOT NULL)||'/'"
                        + "||COALESCE((SELECT e.payload->>'id' FROM conversation_entry e WHERE e.id=c.conversation_entry_id),'-')"
                        + " FROM feedback f JOIN feedback_copy c ON c.feedback_id=f.id WHERE f.id=?",
                String.class,
                feedbackId));
    }

    /**
     * The database refuses the receipt of this feedback, as a database that is briefly away does, until
     * {@link #databaseBack} ends the outage.
     */
    private void databaseAway(UUID feedbackId) {
        schemaOwner.execute("CREATE TABLE IF NOT EXISTS test_receipt_outage(feedback_id uuid PRIMARY KEY)");
        schemaOwner.execute("""
                CREATE OR REPLACE FUNCTION test_receipt_outage() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                 IF EXISTS(SELECT 1 FROM test_receipt_outage WHERE feedback_id=NEW.feedback_id) THEN
                  RAISE EXCEPTION 'database away' USING ERRCODE='08006';
                 END IF;
                 RETURN NEW;
                END $$""");
        schemaOwner.execute("CREATE OR REPLACE TRIGGER test_receipt_outage BEFORE UPDATE ON feedback_copy"
                + " FOR EACH ROW EXECUTE FUNCTION test_receipt_outage()");
        jdbc.update("INSERT INTO test_receipt_outage(feedback_id) VALUES (?)", feedbackId);
    }

    private void databaseBack(UUID feedbackId) {
        jdbc.update("DELETE FROM test_receipt_outage WHERE feedback_id=?", feedbackId);
    }

    /** The outage is schema; schema left behind leaks into every later class that reads the catalog. */
    @AfterEach
    void removeOutage() {
        schemaOwner.execute("DROP TRIGGER IF EXISTS test_receipt_outage ON feedback_copy");
        schemaOwner.execute("DROP FUNCTION IF EXISTS test_receipt_outage()");
        schemaOwner.execute("DROP TABLE IF EXISTS test_receipt_outage");
    }

    @Test
    void shouldRegisterTheTurnTimersAtStartWithTheObjectivesBuckets() {
        assertThat(Arrays.stream(metrics.get(AgentMetrics.AGENT_RUN_FIRST_TOKEN)
                                .tags("kind", "turn", "start", "cold")
                                .timer()
                                .takeSnapshot()
                                .histogramCounts())
                        .map(bucket -> bucket.bucket(TimeUnit.SECONDS)))
                .containsExactly(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 30.0, 60.0);
        assertThat(Arrays.stream(metrics.get(AgentMetrics.CONVERSATION_TURN_DURATION)
                                .timer()
                                .takeSnapshot()
                                .histogramCounts())
                        .map(bucket -> bucket.bucket(TimeUnit.SECONDS)))
                .containsExactly(1.0, 2.0, 5.0, 10.0, 20.0, 30.0, 60.0, 120.0, 300.0, 600.0);
    }

    @Test
    void shouldSettleTheReceiptWhenItsFailedPublicationIsResubmittedAfterATransientFailure() {
        ConversationTurn turn = admittedTurn(Duration.ofMinutes(5));
        UUID shown = claimed(turn);
        databaseAway(shown);

        end(turn, ConversationTurn.Status.DONE, showing(shown, "m7a1b2c3"));

        assertThat(settled(shown)).as("the receipt failed").isEqualTo("UNCONFIRMED/WRITE_STARTED/false/-");
        assertThat(ConversationReceipts.failed(jdbc)).isPositive();
        databaseBack(shown);

        failedPublications.resubmit(ResubmissionOptions.defaults().withMinAge(Duration.ZERO));

        assertThat(settled(shown)).isEqualTo("DELIVERED/POSTED/true/m7a1b2c3");
    }

    @Test
    void shouldSettleTheReceiptAfterARestartWhenTheProcessDiedAfterTheOutcomeCommitted() {
        ConversationTurn turn = admittedTurn(Duration.ofMinutes(5));
        UUID shown = claimed(turn);
        // The process dies after the end committed and before the receipt was stored.
        databaseAway(shown);
        end(turn, ConversationTurn.Status.DONE, showing(shown, "m8a1b2c3"));
        assertThat(settled(shown)).isEqualTo("UNCONFIRMED/WRITE_STARTED/false/-");
        databaseBack(shown);

        // A restart runs every publication that is not complete.
        incompletePublications.resubmitIncompletePublications(publication -> true);

        assertThat(settled(shown)).isEqualTo("DELIVERED/POSTED/true/m8a1b2c3");
    }

    @Test
    void shouldLogAnEndedTurnOnceAndARolledBackEndNever() {
        ConversationTurn turn = admittedTurn(Duration.ofMinutes(5));
        UUID shown = claimed(turn);

        try (var logs = new LogCapture(ConversationTurnCompletion.class)) {
            transactionTemplate.executeWithoutResult(status -> {
                end(turn, ConversationTurn.Status.DONE, showing(shown, "m6a1b2c3"));
                status.setRollbackOnly();
            });
            assertThat(logs.events("conversation.turn.ended")).isEmpty();

            end(turn, ConversationTurn.Status.DONE, showing(shown, "m6a1b2c3"));

            assertThat(logs.events("conversation.turn.ended")).singleElement().satisfies(fields -> {
                assertThat(fields).containsEntry("turn.id", turn.getId().toString());
                assertThat(fields).containsEntry("turn.outcome", "done");
            });
        }
    }

    @Test
    void shouldMakeNoReceiptDueWhenTheEndRollsBack() {
        ConversationTurn turn = admittedTurn(Duration.ofMinutes(5));
        UUID shown = claimed(turn);

        transactionTemplate.executeWithoutResult(status -> {
            end(turn, ConversationTurn.Status.DONE, showing(shown, "m9a1b2c3"));
            status.setRollbackOnly();
        });

        assertThat(settled(shown)).isEqualTo("UNCONFIRMED/WRITE_STARTED/false/-");
        assertThat(turnRepository.findById(turn.getId()).orElseThrow().getStatus())
                .isEqualTo(ConversationTurn.Status.RUNNING);
    }

    @Test
    void shouldSettleTheClaimsOfAnAttemptTheLedgerTookBackUnconfirmed() {
        ConversationTurn turn = admittedTurn(Duration.ofMinutes(5));
        UUID called = claimed(turn);
        ConversationTurn running = turnRepository.findById(turn.getId()).orElseThrow();
        ConversationTurnService.LostAttempt lost = transactionTemplate.execute(
                status -> turns.requeueRunning(workspace.getId(), running.getConversationId())
                        .orElseThrow());

        assertThat(Objects.requireNonNull(lost).attempt()).isEqualTo(running.getAttempt());
        assertThat(settled(called))
                .as("its host may have shown the words, so they are never shown again")
                .isEqualTo("UNCONFIRMED/WRITE_STARTED/true/-");
    }

    @Test
    void shouldDeliverTheFeedbackOnTheMessageThatShowedItWhenAnAppAnswerEndsDone() {
        ConversationTurn turn = admittedTurn(Duration.ofMinutes(5));
        UUID shown = claimed(turn);
        UUID notShown = claimed(turn);

        end(turn, ConversationTurn.Status.DONE, showing(shown, "m0a1b2c3"));

        assertThat(settled(shown)).isEqualTo("DELIVERED/POSTED/true/m0a1b2c3");
        assertThat(settled(notShown)).as("the answer never showed it").isEqualTo("FAILED/FAILED/true/-");
    }

    @Test
    void shouldLeaveTheFeedbackUnconfirmedWhenTheAttemptFailsAfterItsCallAndFailWhatItNeverShowed() {
        ConversationTurn turn = admittedTurn(Duration.ofMinutes(5));
        UUID called = claimed(turn);
        UUID neverCalled = claimed(turn);

        end(turn, ConversationTurn.Status.FAILED, showing(called, "m1a1b2c3"));

        assertThat(settled(called)).isEqualTo("UNCONFIRMED/WRITE_STARTED/true/-");
        assertThat(settled(neverCalled)).isEqualTo("FAILED/FAILED/true/-");
    }

    @Test
    void shouldLeaveTheFeedbackUnconfirmedWhenTheDeveloperStopsTheAnswerThatShowedIt() {
        ConversationTurn turn = admittedTurn(Duration.ofMinutes(5));
        UUID called = claimed(turn);
        turns.stop(workspace.getId(), turn.getId());

        end(turn, ConversationTurn.Status.STOPPED, showing(called, "m2a1b2c3"));

        assertThat(settled(called)).isEqualTo("UNCONFIRMED/WRITE_STARTED/true/-");
    }

    @Test
    void shouldSettleALostAttemptsClaimUnconfirmedSoItsRetryCannotClaimItAgain() {
        ConversationTurn first = admittedTurn(Duration.ofSeconds(-1));
        UUID called = claimed(first);

        reaper.reap();

        assertThat(settled(called)).isEqualTo("UNCONFIRMED/WRITE_STARTED/true/-");
        ConversationTurn second = claims.claimNext().orElseThrow();
        assertThat(second.getAttempt()).isEqualTo(2);
        assertThat(feedback.claimConversationCopy(workspace.getId(), called, second.getId(), second.getAttempt()))
                .isEmpty();
        end(second, ConversationTurn.Status.DONE, showing(called, "m3a1b2c3"));
        assertThat(settled(called))
                .as("the retry's end settles none of the lost attempt's claims")
                .isEqualTo("UNCONFIRMED/WRITE_STARTED/true/-");
    }

    /** A claimed attempt admitted at {@link #ADMITTED}'s rates. */
    private ConversationTurn admittedTurn(Duration lease) {
        turns.submit(
                workspace.getId(),
                main.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP,
                        "Question",
                        UUID.randomUUID().toString(),
                        null,
                        null));
        ConversationTurn turn = claims.claimNext().orElseThrow();
        admit(turn);
        if (lease.isNegative()) expire(turn);
        return turn;
    }

    private void expire(ConversationTurn turn) {
        claims.expireLease(turn);
    }

    /** Admits the attempt as the worker does. */
    private void admit(ConversationTurn turn) {
        transactionTemplate.executeWithoutResult(tx -> turns.admit(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                ConversationTurnAdmission.of(ADMITTED, Objects.requireNonNull(ADMITTED.priceSnapshot()))
                        .toJson()));
    }

    /** One proxied call of the attempt at its admitted rates, opened and measured as the proxy does. */
    private void proxyCall(ConversationTurn turn, long input) {
        transactionTemplate.executeWithoutResult(tx -> {
            UUID call = recorder.open(new LlmUsageRecorder.OpenCall(
                    turn.getWorkspaceId(),
                    turn.getRunId(),
                    turn.getAttempt(),
                    UsageSourceType.CONVERSATION_TURN.name(),
                    UsageSourceType.CONVERSATION_TURN,
                    turn.getId(),
                    turn.getAttempt(),
                    null,
                    ModelKind.CHAT,
                    null,
                    ADMITTED.upstreamModelId(),
                    Objects.requireNonNull(ADMITTED.priceSnapshot()),
                    null));
            recorder.complete(call, turn.getWorkspaceId(), new LlmUsageRecorder.CallUsage(input, 10, 0, 0, 0, 0));
        });
    }

    /** The call rows of one attempt in the usage ledger, summed. */
    private record Billed(int attempt, long inputTokens, int calls, String provenance, BigDecimal costUsd) {}

    private List<Billed> ledger(ConversationTurn turn) {
        return jdbc.query(
                "SELECT source_attempt, SUM(input_tokens), SUM(total_calls), MIN(usage_provenance), SUM(cost_usd)"
                        + " FROM llm_usage_event WHERE source_id = ? GROUP BY source_attempt ORDER BY source_attempt",
                (rs, row) -> new Billed(
                        rs.getInt(1),
                        rs.getLong(2),
                        rs.getInt(3),
                        Objects.requireNonNull(rs.getString(4)),
                        Objects.requireNonNull(rs.getBigDecimal(5))),
                turn.getId());
    }

    @Test
    void shouldKeepEachAttemptsCallsWhenATakenBackAttemptIsBilledAfterTheNextOneEnded() {
        ConversationTurn first = admittedTurn(Duration.ofMinutes(5));
        proxyCall(first, 30_000);
        ConversationTurnService.LostAttempt lost = Objects.requireNonNull(
                transactionTemplate.execute(status -> turns.requeueRunning(workspace.getId(), first.getConversationId())
                        .orElseThrow()));
        ConversationReceipts.awaitSettled(jdbc);
        ConversationTurn second = claims.claimNext().orElseThrow();
        admit(second);
        proxyCall(second, 70_000);
        end(second, ConversationTurn.Status.DONE, List.of());

        // The billing of the taken-back attempt runs again after the turn's next attempt ended.
        completion.billTakenBack(new ConversationLedgerEvents.AttemptTakenBack(
                workspace.getId(), first.getId(), lost.attempt(), lost.admission(), lost.startedAt(), lost.lostAt()));

        ConversationTurn row = turnRepository.findById(first.getId()).orElseThrow();
        assertThat(row.getAttempt()).isEqualTo(2);
        assertThat(ledger(first))
                .extracting(Billed::attempt, Billed::inputTokens)
                .containsExactly(tuple(1, 30_000L), tuple(2, 70_000L));
    }

    @Test
    void shouldBillEveryProxiedCallOfTheAttemptWhenItEnds() {
        ConversationTurn turn = admittedTurn(Duration.ofMinutes(5));
        proxyCall(turn, 50_000);
        proxyCall(turn, 50_000);

        completion.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new ConversationTurnCompletion.Ending(ConversationTurn.Status.DONE, List.of(), null, null, null, null));

        assertThat(ledger(turn)).singleElement().satisfies(row -> {
            assertThat(row.attempt()).isEqualTo(1);
            assertThat(row.inputTokens()).as("the sum of the proxy's calls").isEqualTo(100_000);
            assertThat(row.provenance()).isEqualTo("PROXY");
            assertThat(row.calls()).isEqualTo(2);
            assertThat(row.costUsd()).isEqualByComparingTo("1.00");
        });
    }

    @Test
    void shouldBillNothingWhenTheAttemptMadeNoCall() {
        ConversationTurn turn = admittedTurn(Duration.ofMinutes(5));

        completion.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new ConversationTurnCompletion.Ending(
                        ConversationTurn.Status.FAILED, List.of(), "broken", null, null, null));

        assertThat(ledger(turn)).isEmpty();
    }

    @Test
    void shouldRecordWhyTheAttemptFailedOnTheTurnWhenItEndsFailed() {
        ConversationTurn turn = admittedTurn(Duration.ofMinutes(5));
        proxyCall(turn, 50_000);

        completion.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new ConversationTurnCompletion.Ending(
                        ConversationTurn.Status.FAILED,
                        List.of(),
                        "Hephaestus could not finish this reply.",
                        "its Hephaestus host reported ERROR: provider_unavailable",
                        new ConversationTurn.FailedCall("upstream_status", 503),
                        null));

        ConversationTurn row = turnRepository.findById(turn.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(row.getFailureCause()).isEqualTo("its Hephaestus host reported ERROR: provider_unavailable");
        assertThat(row.getFailedCall()).isEqualTo("upstream_status");
        assertThat(row.getFailedCallStatus()).isEqualTo(503);
        assertThat(ledger(turn))
                .as("the model call that completed before it failed")
                .singleElement()
                .extracting(Billed::calls)
                .isEqualTo(1);
    }

    @Test
    void shouldCutACauseAndACallKindToTheirColumnsAndStillEndTheTurn() {
        ConversationTurn turn = admittedTurn(Duration.ofMinutes(5));

        completion.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new ConversationTurnCompletion.Ending(
                        ConversationTurn.Status.FAILED,
                        List.of(),
                        "Hephaestus could not finish this reply.",
                        "c".repeat(300),
                        new ConversationTurn.FailedCall("k".repeat(100), null),
                        null));

        ConversationTurn row = turnRepository.findById(turn.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(row.getFailureCause()).isEqualTo("c".repeat(200));
        assertThat(row.getFailedCall()).isEqualTo("k".repeat(64));
    }

    @Test
    void shouldRecordNoFailureOnTheTurnWhenItEndsDone() {
        ConversationTurn turn = admittedTurn(Duration.ofMinutes(5));

        completion.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getRunId()),
                new ConversationTurnCompletion.Ending(
                        ConversationTurn.Status.DONE,
                        List.of(),
                        null,
                        null,
                        new ConversationTurn.FailedCall("upstream_idle", 200),
                        null));

        ConversationTurn row = turnRepository.findById(turn.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ConversationTurn.Status.DONE);
        assertThat(row.getFailureCause()).isNull();
        assertThat(row.getFailedCall())
                .as("a retried call that failed does not fail the turn")
                .isNull();
        assertThat(row.getFailedCallStatus()).isNull();
    }

    @Test
    void shouldBillEachLostAttemptFromTheProxyWhenTheReaperTakesItsLeaseBack() {
        ConversationTurn first = admittedTurn(Duration.ofSeconds(-1));
        jdbc.update(
                "UPDATE conversation_turn SET queued_at = now() - interval '10 minutes' WHERE id = ?", first.getId());
        Instant queuedBeforeLoss =
                turnRepository.findById(first.getId()).orElseThrow().getQueuedAt();
        proxyCall(first, 30_000);

        reaper.reap();

        ConversationTurn requeued = turnRepository.findById(first.getId()).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(ConversationTurn.Status.QUEUED);
        assertThat(requeued.getQueuedAt()).isAfter(queuedBeforeLoss);
        ConversationTurn second = claims.claimNext().orElseThrow();
        admit(second);
        expire(second);
        proxyCall(second, 70_000);

        reaper.reap();

        assertThat(turnRepository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(ledger(first))
                .extracting(Billed::attempt, Billed::inputTokens)
                .containsExactly(tuple(1, 30_000L), tuple(2, 70_000L));
    }
}
