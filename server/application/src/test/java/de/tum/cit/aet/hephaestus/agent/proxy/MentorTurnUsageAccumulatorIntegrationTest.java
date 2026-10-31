package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorAttemptUsageService;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.MeasurementVerification;
import de.tum.cit.aet.hephaestus.agent.usage.UnpricedLedgerRow;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The accumulator bean is absent from the test context ({@code hephaestus.runtime.worker.enabled=false}),
 * so it is constructed directly here, with the transaction manager that opens its own transaction. Each attempt is admitted through {@link MentorAttemptUsageService#admit}, as the worker
 * admits it, so its usage measurement exists before any call.
 */
@Tag("integration")
class MentorTurnUsageAccumulatorIntegrationTest extends AbstractWorkspaceIntegrationTest {

    /** $10 per 1M input tokens, nothing for output: the fixture's admitted rates. */
    private static final MentorTurnAdmission ADMITTED = MentorTurnAdmission.of(
            LlmCatalogTestFixtures.admittedMentorConfig(),
            Objects.requireNonNull(LlmCatalogTestFixtures.admittedMentorConfig().priceSnapshot()));

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorAttemptUsageService attemptUsage;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MentorTurnCompletion completion;

    @Autowired
    private LlmUsageEventRepository ledgerRows;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private SimpleMeterRegistry meters;
    private MentorTurnUsageAccumulator accumulator;
    private Workspace workspace;
    private MentorConversation conversation;

    @BeforeEach
    void setUpFixture() {
        meters = new SimpleMeterRegistry();
        accumulator = new MentorTurnUsageAccumulator(attemptUsage, transactionManager, meters);

        User owner = persistUser("mentor-usage-owner-" + UUID.randomUUID());
        workspace = createWorkspace(
                "mentor-usage-" + UUID.randomUUID().toString().substring(0, 8),
                "Mentor Usage",
                "mentor-usage-org",
                AccountType.ORG,
                owner);
        conversation = conversations.findOrCreateMain(workspace.getId(), accountId(owner));
    }

    /** A turn a worker claimed, so its first attempt holds the lease. */
    private MentorTurn runningTurn() {
        return runningTurn(Duration.ofMinutes(5));
    }

    /** The admitted attempt's lease runs out, so the take-back that follows is the lease reaper's. */
    private void leaseRanOut(MentorTurn turn) {
        jdbc.update("UPDATE mentor_turn SET leased_until = now() - interval '1 second' WHERE id = ?", turn.getId());
    }

    private MentorTurn runningTurn(Duration lease) {
        turns.submit(
                workspace.getId(),
                conversation.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP,
                        "How did my last review go?",
                        UUID.randomUUID().toString(),
                        null,
                        null));
        return turns.claimNext(lease).orElseThrow();
    }

    private Instant measuredDate(MentorTurn turn, int attempt) {
        return Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT occurred_at FROM mentor_turn_attempt_usage WHERE turn_id = ? AND attempt = ?",
                        Timestamp.class,
                        turn.getId(),
                        attempt))
                .toInstant();
    }

    @Test
    @DisplayName("the take-back dates the attempt; a late call and a delayed billing keep that month")
    void shouldKeepTheTakeBackMonthWhenTheLateCallAndTheBillingArriveLater() {
        MentorTurn turn = runningTurn();
        attemptUsage.admit(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                ADMITTED);
        leaseRanOut(turn);
        var first = attempt(turn);
        accumulate(first, new ProxyTokenUsage(100_000, 0, 0, 0, 0));
        // The take-back commits; its billing has not run yet.
        MentorTurnService.LostAttempt lost = Objects.requireNonNull(transactionTemplate.execute(
                tx -> turns.expireLease(turn.getId()).orElseThrow()));
        assertThat(measuredDate(turn, first.number()))
                .as("dated in the take-back's transaction, before the requeue reset the turn")
                .isEqualTo(lost.lostAt());
        // The database clock cannot be moved, so the take-back's date is set to an earlier month: from here on it is
        // the only fact that may give the attempt a month.
        Instant earlierMonth = lost.lostAt().minus(40, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        jdbc.update(
                "UPDATE mentor_turn_attempt_usage SET occurred_at = ? WHERE turn_id = ? AND attempt = ?",
                Timestamp.from(earlierMonth),
                turn.getId(),
                first.number());
        MentorTurn retried = turns.claimNext(Duration.ofMinutes(5)).orElseThrow();
        attemptUsage.admit(
                retried.getWorkspaceId(),
                retried.getId(),
                retried.getAttempt(),
                Objects.requireNonNull(retried.getLeaseId()),
                ADMITTED);

        // The already-dispatched call returns now, in the current month, and projects the attempt first.
        accumulate(first, new ProxyTokenUsage(50_000, 0, 0, 0, 0));
        // The delayed billing runs after it.
        transactionTemplate.executeWithoutResult(tx -> completion.billLostAttempt(lost));

        assertThat(ledger(first)).singleElement().satisfies(row -> {
            assertThat(row.occurredAt())
                    .as("the take-back's month, not the call's or the billing's")
                    .isEqualTo(earlierMonth);
            assertThat(row.inputTokens()).isEqualTo(150_000);
            assertThat(row.calls()).isEqualTo(2);
            assertThat(row.costUsd()).isEqualByComparingTo("1.50");
        });
        assertThat(ledger(attempt(retried))).isEmpty();
    }

    @Test
    @DisplayName("an attempt taken back before any call is dated then, so a call that returns later keeps that date")
    void shouldDateAnAttemptWithoutCallsAtItsTakeBack() {
        MentorTurn turn = runningTurn();
        attemptUsage.admit(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                ADMITTED);
        leaseRanOut(turn);
        var first = attempt(turn);
        MentorTurnService.LostAttempt lost = Objects.requireNonNull(transactionTemplate.execute(tx -> {
            MentorTurnService.LostAttempt taken =
                    turns.expireLease(turn.getId()).orElseThrow();
            completion.billLostAttempt(taken);
            return taken;
        }));
        assertThat(ledger(first)).as("no call, no row").isEmpty();

        accumulate(first, new ProxyTokenUsage(20_000, 0, 0, 0, 0));

        assertThat(ledger(first)).singleElement().satisfies(row -> {
            assertThat(row.occurredAt()).isEqualTo(lost.lostAt());
            assertThat(row.inputTokens()).isEqualTo(20_000);
        });
    }

    /** A claimed turn whose attempt the worker admitted, as {@code MentorTurnStarter} admits it. */
    private MentorTurn admittedTurn(MentorTurnAdmission admitted) {
        MentorTurn turn = runningTurn();
        attemptUsage.admit(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                admitted);
        return turn;
    }

    @Test
    @DisplayName("a settlement that fails inside its transaction is counted and never reaches the proxied response")
    void shouldCountAndSwallowASettlementThatFailsInsideItsTransaction() {
        var settlement = mock(MentorAttemptUsageService.class);
        var mandatory = new TransactionTemplate(transactionManager);
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        // As the MANDATORY settlement fails: it marks the caller's transaction rollback-only, then throws.
        when(settlement.settleCall(any(), anyInt(), any()))
                .thenAnswer(call -> mandatory.execute(status -> {
                    throw new IllegalStateException("lock timeout");
                }));
        var failing = new MentorTurnUsageAccumulator(settlement, transactionManager, meters);

        assertThatCode(() -> failing.accumulate(attempt(runningTurn()), new ProxyTokenUsage(10, 1, 0, 0, 0)))
                .doesNotThrowAnyException();
        assertThat(meters.counter("llm.proxy.usage.mentor.failure").count()).isEqualTo(1.0);
    }

    private ProxyRouting.BilledAttempt attempt(MentorTurn turn) {
        return new ProxyRouting.BilledAttempt(
                UsageSourceType.MENTOR_TURN, turn.getId(), turn.getAttempt(), BigDecimal.ZERO, turn.getLeaseId());
    }

    private void accumulate(ProxyRouting.BilledAttempt attempt, ProxyTokenUsage usage) {
        accumulator.accumulate(attempt, usage);
    }

    private void end(MentorTurn turn) {
        completion.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                new MentorTurnCompletion.Ending(MentorTurn.Status.DONE, List.of(), null, null, null));
    }

    private MentorTurn stored(MentorTurn turn) {
        return turnRepository.findById(turn.getId()).orElseThrow();
    }

    private int measurements(MentorTurn turn) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM mentor_turn_attempt_usage WHERE turn_id = ?", Integer.class, turn.getId()));
    }

    @Test
    @DisplayName("successive calls add onto the attempt's row rather than replacing it")
    void shouldAccumulateRepeatedCallsWhenTheAttemptHoldsItsLease() {
        MentorTurn turn = admittedTurn(ADMITTED);

        accumulate(attempt(turn), new ProxyTokenUsage(100, 40, 10, 25, 15));
        accumulate(attempt(turn), new ProxyTokenUsage(200, 60, 5, 0, 0));
        accumulate(attempt(turn), new ProxyTokenUsage(300, 80, 0, 0, 0));

        MentorTurn row = stored(turn);
        assertThat(row.getLlmCalls()).isEqualTo(3);
        assertThat(row.getInputTokens()).isEqualTo(600);
        assertThat(row.getOutputTokens()).isEqualTo(180);
        assertThat(row.getReasoningTokens()).isEqualTo(15);
        assertThat(row.getCachedTokens()).isEqualTo(25);
        assertThat(row.getCacheWriteTokens()).isEqualTo(15);
        assertThat(jdbc.queryForMap(
                        "SELECT calls, input_tokens FROM mentor_turn_attempt_usage WHERE turn_id = ? AND attempt = ?",
                        turn.getId(),
                        turn.getAttempt()))
                .containsEntry("calls", 3)
                .containsEntry("input_tokens", 600L);
    }

    @Test
    @DisplayName("a call that lands after the attempt ended is settled on that attempt, not on the turn's counters")
    void shouldSettleACallOnItsOwnAttemptWhenTheAttemptAlreadyEnded() {
        MentorTurn turn = admittedTurn(ADMITTED);
        accumulate(attempt(turn), new ProxyTokenUsage(100, 40, 0, 0, 0));

        turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                new MentorTurnService.Ending(MentorTurn.Status.DONE, List.of(), null));
        accumulate(attempt(turn), new ProxyTokenUsage(999, 999, 0, 0, 0));

        MentorTurn row = stored(turn);
        assertThat(row.getLlmCalls())
                .as("the turn's counters are only the running attempt's")
                .isEqualTo(1);
        assertThat(row.getInputTokens()).isEqualTo(100);
        assertThat(ledger(attempt(turn))).singleElement().satisfies(billed -> {
            assertThat(billed.inputTokens()).isEqualTo(1_099);
            assertThat(billed.calls()).isEqualTo(2);
        });
        assertThat(meters.counter("llm.proxy.usage.mentor.superseded").count()).isZero();
    }

    /** The single financial row of the fixture's original owning attempt. */
    private record Billed(long inputTokens, int calls, BigDecimal costUsd, Instant occurredAt) {}

    private List<Billed> ledger(ProxyRouting.BilledAttempt attempt) {
        return jdbc.query(
                "SELECT input_tokens, total_calls, cost_usd, occurred_at FROM llm_usage_event"
                        + " WHERE workspace_id = ? AND source_type = 'MENTOR_TURN' AND source_id = ?"
                        + " AND source_attempt = ?",
                (rs, row) -> new Billed(
                        rs.getLong(1),
                        rs.getInt(2),
                        Objects.requireNonNull(rs.getBigDecimal(3)),
                        Objects.requireNonNull(rs.getTimestamp(4)).toInstant()),
                workspace.getId(),
                attempt.sourceId(),
                attempt.number());
    }

    @Test
    @DisplayName("a call observed before end settles onto its original attempt after the turn already ended")
    void shouldRetainLateProxyUsageOnTheAttemptThatAlreadyEnded() {
        MentorTurn turn = runningTurn();
        // The worker's admission, which also creates the attempt's usage measurement before any call.
        attemptUsage.admit(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                ADMITTED);
        // The proxy keeps this identity from dispatch until its observed usage is settled.
        var owningAttempt = attempt(turn);
        accumulate(owningAttempt, new ProxyTokenUsage(100_000, 40, 0, 0, 0));
        completion.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                new MentorTurnCompletion.Ending(MentorTurn.Status.DONE, List.of(), null, null, null));

        MentorTurn ended = stored(turn);
        assertThat(ended.getStatus()).isEqualTo(MentorTurn.Status.DONE);
        assertThat(ended.getLeasedUntil()).isNull();
        Instant finished = Objects.requireNonNull(ended.getFinishedAt());
        Billed original = ledger(owningAttempt).getFirst();
        assertThat(original.inputTokens()).isEqualTo(100_000);
        assertThat(original.calls()).isEqualTo(1);
        assertThat(original.costUsd()).isEqualByComparingTo("1.00");

        // No new model request: this is the already-dispatched call's final observed usage callback.
        accumulate(owningAttempt, new ProxyTokenUsage(50_000, 20, 0, 0, 0));

        assertThat(ledger(owningAttempt)).singleElement().satisfies(row -> {
            assertThat(row.inputTokens())
                    .as("all observed calls belong to their original owning attempt")
                    .isEqualTo(150_000);
            assertThat(row.calls()).isEqualTo(2);
            assertThat(row.costUsd()).isEqualByComparingTo("1.50");
            assertThat(row.occurredAt()).isEqualTo(original.occurredAt());
        });
        MentorTurn after = stored(turn);
        assertThat(after.getStatus()).isEqualTo(MentorTurn.Status.DONE);
        assertThat(after.getAttempt()).isEqualTo(owningAttempt.number());
        assertThat(after.getFinishedAt()).isEqualTo(finished);
    }

    @Test
    @DisplayName("a late call of a taken-back attempt is billed at that attempt's rates, never on the next attempt")
    void shouldBillALateCallAtItsOwnAttemptsRatesWhenTheTurnRunsItsNextAttempt() {
        MentorTurn turn = admittedTurn(ADMITTED);
        var first = attempt(turn);
        accumulate(first, new ProxyTokenUsage(100_000, 0, 0, 0, 0));
        transactionTemplate.executeWithoutResult(
                tx -> assertThat(turns.requeueRunning(workspace.getId(), conversation.getId()))
                        .isPresent());
        MentorTurn retried = turns.claimNext(Duration.ofMinutes(5)).orElseThrow();
        assertThat(retried.getAttempt()).isEqualTo(first.number() + 1);
        // The retry runs on other rates and another purse.
        var otherPrice = new LlmPriceSnapshot(
                UsageFundingSource.WORKSPACE,
                UsagePricingState.PRICED,
                null,
                99L,
                new BigDecimal("100"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO);
        attemptUsage.admit(
                retried.getWorkspaceId(),
                retried.getId(),
                retried.getAttempt(),
                Objects.requireNonNull(retried.getLeaseId()),
                new MentorTurnAdmission(
                        ADMITTED.model(),
                        otherPrice,
                        ADMITTED.route(),
                        ADMITTED.contextWindow(),
                        ADMITTED.maxOutputTokens(),
                        ADMITTED.reasoningEffort()));

        accumulate(first, new ProxyTokenUsage(50_000, 0, 0, 0, 0));
        accumulate(attempt(retried), new ProxyTokenUsage(7_000, 0, 0, 0, 0));

        assertThat(ledger(first)).singleElement().satisfies(row -> {
            assertThat(row.inputTokens()).isEqualTo(150_000);
            assertThat(row.calls()).isEqualTo(2);
            assertThat(row.costUsd()).as("at the first attempt's $10 per 1M").isEqualByComparingTo("1.50");
        });
        assertThat(jdbc.queryForObject(
                        "SELECT funding_source FROM llm_usage_event WHERE source_type = 'MENTOR_TURN'"
                                + " AND source_id = ? AND source_attempt = ?",
                        String.class,
                        turn.getId(),
                        first.number()))
                .isEqualTo(UsageFundingSource.INSTANCE.name());
        assertThat(ledger(attempt(retried)))
                .as("the running attempt is billed when it ends")
                .isEmpty();
        MentorTurn row = stored(turn);
        assertThat(row.getAttempt()).isEqualTo(retried.getAttempt());
        assertThat(row.getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(row.getLlmCalls()).isEqualTo(1);
        assertThat(row.getInputTokens()).isEqualTo(7_000);
    }

    @Test
    @DisplayName("a call authenticated for another attempt is dropped")
    void shouldDropACallWhenItsAttemptNumberIsNotTheRunningOne() {
        MentorTurn turn = admittedTurn(ADMITTED);
        var stale = new ProxyRouting.BilledAttempt(
                UsageSourceType.MENTOR_TURN, turn.getId(), turn.getAttempt() + 1, BigDecimal.ZERO, turn.getLeaseId());

        accumulate(stale, new ProxyTokenUsage(500, 5, 0, 0, 0));

        assertThat(stored(turn).getLlmCalls()).isZero();
        assertThat(measurements(turn))
                .as("no measurement is created for the call")
                .isEqualTo(1);
        assertThat(meters.counter("llm.proxy.usage.mentor.superseded").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a call of an attempt admitted without a measurement is dropped, and none is created for it")
    void shouldDropACallWhenItsAttemptHasNoMeasurement() {
        MentorTurn turn = runningTurn();
        turns.admit(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                ADMITTED.toJson());

        accumulate(attempt(turn), new ProxyTokenUsage(500, 5, 0, 0, 0));
        end(turn);

        assertThat(measurements(turn)).isZero();
        assertThat(ledger(attempt(turn))).isEmpty();
        assertThat(meters.counter("llm.proxy.usage.mentor.superseded").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a call the provider answered without usage counts nothing")
    void shouldCountNothingWhenTheProviderReportedNoUsage() {
        MentorTurn turn = admittedTurn(ADMITTED);

        transactionTemplate.executeWithoutResult(tx -> accumulator.accumulate(attempt(turn), null));
        end(turn);

        assertThat(stored(turn).getLlmCalls()).isZero();
        assertThat(ledger(attempt(turn)))
                .as("an attempt that made no measured call is not billed")
                .isEmpty();
    }

    @Test
    @DisplayName("after an erasure forgot the turn, a late call writes nothing back into the ledger")
    void shouldDetachTheLedgerAndDropALateCallWhenAnErasureForgotTheTurn() {
        MentorTurn turn = admittedTurn(ADMITTED);
        accumulate(attempt(turn), new ProxyTokenUsage(100_000, 0, 0, 0, 0));
        end(turn);

        attemptUsage.forgetTurn(workspace.getId(), turn.getId());

        String rows =
                "SELECT source_id, review_id, input_tokens, total_calls, cost_usd, occurred_at FROM llm_usage_event"
                        + " WHERE workspace_id = ? AND source_type = 'MENTOR_TURN'";
        var detached = jdbc.queryForList(rows, workspace.getId());
        assertThat(detached).singleElement().satisfies(row -> {
            assertThat(row.get("source_id")).isNotEqualTo(turn.getId());
            assertThat(row.get("review_id")).isNull();
            assertThat(row.get("input_tokens")).isEqualTo(100_000L);
        });
        assertThat(measurements(turn)).isZero();

        accumulate(attempt(turn), new ProxyTokenUsage(50_000, 0, 0, 0, 0));

        assertThat(jdbc.queryForList(rows, workspace.getId())).isEqualTo(detached);
        assertThat(measurements(turn)).isZero();
        assertThat(meters.counter("llm.proxy.usage.mentor.superseded").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an erasure keeps the spend a running attempt observed before it forgot the attempt")
    void shouldKeepTheObservedSpendOfARunningAttemptWhenAnErasureForgetsIt() {
        MentorTurn turn = admittedTurn(ADMITTED);
        accumulate(attempt(turn), new ProxyTokenUsage(100_000, 0, 0, 0, 0));

        attemptUsage.forgetTurn(workspace.getId(), turn.getId());
        end(turn);

        assertThat(jdbc.queryForList(
                        "SELECT input_tokens, source_id FROM llm_usage_event WHERE workspace_id = ?"
                                + " AND source_type = 'MENTOR_TURN'",
                        workspace.getId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("input_tokens")).isEqualTo(100_000L);
                    assertThat(row.get("source_id")).isNotEqualTo(turn.getId());
                });
        assertThat(ledger(attempt(turn)))
                .as("the end finds no measurement and bills nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("a late call keeps the price the repricing pass resolved for the attempt")
    void shouldKeepTheResolvedPriceWhenALateCallFollowsRepricing() {
        var unpriced = new MentorTurnAdmission(
                ADMITTED.model(),
                LlmPriceSnapshot.unpricedInstance(),
                ADMITTED.route(),
                ADMITTED.contextWindow(),
                ADMITTED.maxOutputTokens(),
                ADMITTED.reasoningEffort());
        MentorTurn turn = admittedTurn(unpriced);
        accumulate(attempt(turn), new ProxyTokenUsage(100_000, 0, 0, 0, 0));
        end(turn);
        LlmPriceSnapshot resolved = new LlmPriceSnapshot(
                UsageFundingSource.INSTANCE,
                UsagePricingState.PRICED,
                44L,
                null,
                new BigDecimal("2"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO);
        // What the repricing pass does to the verified unpriced row.
        transactionTemplate.executeWithoutResult(tx -> {
            UUID id = Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT id FROM llm_usage_event WHERE source_type = 'MENTOR_TURN' AND source_id = ?",
                    UUID.class,
                    turn.getId()));
            UnpricedLedgerRow read = new UnpricedLedgerRow(
                    id,
                    workspace.getId(),
                    ADMITTED.model(),
                    UsageFundingSource.INSTANCE,
                    100_000,
                    0,
                    0,
                    0,
                    0,
                    null,
                    null,
                    MeasurementVerification.VERIFIED);
            assertThat(ledgerRows.applyResolvedPrice(id, new BigDecimal("0.200000"), resolved, read))
                    .isEqualTo(1);
        });

        accumulate(attempt(turn), new ProxyTokenUsage(50_000, 0, 0, 0, 0));

        assertThat(ledger(attempt(turn))).singleElement().satisfies(row -> {
            assertThat(row.inputTokens()).isEqualTo(150_000);
            assertThat(row.costUsd()).as("150k at the resolved $2 per 1M").isEqualByComparingTo("0.30");
        });
        assertThat(jdbc.queryForObject(
                        "SELECT applied_price_id FROM llm_usage_event WHERE source_type = 'MENTOR_TURN'"
                                + " AND source_id = ?",
                        Long.class,
                        turn.getId()))
                .isEqualTo(44L);
    }
}
