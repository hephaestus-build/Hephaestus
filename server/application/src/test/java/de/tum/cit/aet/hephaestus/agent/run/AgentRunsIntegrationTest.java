package de.tum.cit.aet.hephaestus.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.job.ReviewComposeRunKind;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.observability.LogCapture;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("Agent runs over PostgreSQL Integration")
class AgentRunsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AgentRuns runs;

    @Autowired
    private AgentRunRepository repository;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LlmUsageRecorder usage;

    @Autowired
    private ReviewComposeRunKind composeKind;

    private long workspace;
    private long other;

    @BeforeEach
    void setUp() {
        // The claim offers the next run of every workspace, so no run of another test may wait.
        databaseTestUtils.cleanDatabase();
        workspace = workspaces
                .save(TestEntities.activeWorkspace("runs-" + UUID.randomUUID()))
                .getId();
        other = workspaces
                .save(TestEntities.activeWorkspace("runs-other-" + UUID.randomUUID()))
                .getId();
    }

    @Test
    void shouldCountOnlyInFlightReviewWorkForTheServerDrain() {
        var measuring = enqueue(workspace, AgentRunBand.BATCH, "review:drain");
        var queued = enqueue(other, AgentRunBand.BATCH, "review:waiting");
        var composition = Objects.requireNonNull(transactions.execute(status ->
                runs.enqueue(other, AgentRunKind.REVIEW_COMPOSE, AgentRunBand.BATCH, "review:composing", null)));
        enqueueTurn(workspace, "conversation:drain");
        var measureLease = claimSubject(workspace, "review:drain").orElseThrow();
        transactions.executeWithoutResult(status -> {
            assertThat(runs.claimSubject(other, "review:composing", Set.of(AgentRunKind.REVIEW_COMPOSE)))
                    .isPresent();
            assertThat(runs.claimSubject(workspace, "conversation:drain", Set.of(AgentRunKind.TURN)))
                    .isPresent();
        });
        assertThat(runs.leasedReviews()).isEqualTo(2);
        assertThat(state(queued.id())).isEqualTo("QUEUED");
        assertThat(state(composition.id())).isEqualTo("LEASED");
        transactions.executeWithoutResult(status -> {
            assertThat(runs.renew(measureLease.fence())).isPresent();
            assertThat(runs.end(measureLease.fence(), AgentRunStopReason.COMPLETED))
                    .isEqualTo(AgentRuns.EndOutcome.ENDED);
        });
        assertThat(state(measuring.id())).isEqualTo("ENDED");
        assertThat(runs.leasedReviews()).isEqualTo(1);
    }

    @Test
    void shouldOfferEveryRunOfABandBeforeAnyRunOfTheNextBand() {
        AgentRunRef background = enqueue(workspace, AgentRunBand.BACKGROUND, "review:a");
        AgentRunRef batch = enqueue(workspace, AgentRunBand.BATCH, "review:b");
        AgentRunRef interactive = enqueue(workspace, AgentRunBand.INTERACTIVE, "conversation:c");

        assertThat(claim()).map(AgentRunLease::runId).contains(interactive.id());
        assertThat(claim()).map(AgentRunLease::runId).contains(batch.id());
        assertThat(claim()).map(AgentRunLease::runId).contains(background.id());
        assertThat(claim()).isEmpty();
    }

    @Test
    void shouldClaimABackgroundRunWithinItsAgingBoundUnderASteadyStreamOfBatchRuns() {
        Duration aging = runs.capacity().bandAging();
        AgentRunRef backfill = enqueue(workspace, AgentRunBand.BACKGROUND, "review:backfill");
        // Steady demand: a fresh batch run is due whenever a claim asks.
        for (int round = 0; round < 3; round++) {
            enqueue(other, AgentRunBand.BATCH, "review:request-" + round);
            assertThat(claim())
                    .as("a fresh backfill waits")
                    .hasValueSatisfying(lease -> assertThat(lease.runId()).isNotEqualTo(backfill.id()));
        }
        enqueue(other, AgentRunBand.BATCH, "review:request-late");
        // The backfill has waited one aging bound since it was due.
        jdbc.update(
                "UPDATE agent_run SET available_at = now() - make_interval(secs => ?) WHERE id = ?",
                aging.toSeconds() + 1,
                backfill.id());

        assertThat(claim())
                .map(AgentRunLease::runId)
                .as("after one aging bound the backfill orders with the batch band, and it is older")
                .contains(backfill.id());
    }

    @Test
    void shouldLeaseOnlyTheNamedSubjectsOldestRunToItsSandboxAndNoSecondWhileOneIsLeased() {
        AgentRunRef first = enqueue(workspace, AgentRunBand.BATCH, "review:sandboxed");
        AgentRunRef second = enqueue(workspace, AgentRunBand.BATCH, "review:sandboxed");
        AgentRunRef elsewhere = enqueue(other, AgentRunBand.INTERACTIVE, "review:sandboxed");
        AgentRunRef sibling = enqueue(workspace, AgentRunBand.INTERACTIVE, "review:sibling");

        assertThat(claimSubject(workspace, "review:sandboxed"))
                .map(AgentRunLease::runId)
                .contains(first.id());
        assertThat(claimSubject(workspace, "review:sandboxed"))
                .as("one leased run of a subject at a time")
                .isEmpty();
        assertThat(claimSubject(other, "review:sandboxed"))
                .map(AgentRunLease::runId)
                .contains(elsewhere.id());
        assertThat(claimSubject(workspace, "review:sibling"))
                .map(AgentRunLease::runId)
                .contains(sibling.id());
        assertThat(repository.findById(second.id())).map(AgentRun::getState).contains(AgentRunState.QUEUED);
        Optional<AgentRunLease> none = Objects.requireNonNull(
                transactions.execute(status -> runs.claimSubject(workspace, "review:sibling", Set.<AgentRunKind>of())));
        assertThat(none).as("a sandbox leases only the kinds it drives").isEmpty();
    }

    @Test
    void shouldNeverAgeQueuedWorkAheadOfAnInteractiveRun() {
        AgentRunRef backfill = enqueue(workspace, AgentRunBand.BACKGROUND, "review:old-backfill");
        jdbc.update("UPDATE agent_run SET available_at = now() - interval '1 day' WHERE id = ?", backfill.id());
        AgentRunRef turn = enqueue(workspace, AgentRunBand.INTERACTIVE, "conversation:asked");

        assertThat(claim()).map(AgentRunLease::runId).contains(turn.id());
        assertThat(claim()).map(AgentRunLease::runId).contains(backfill.id());
    }

    @Test
    void shouldOfferTheWorkspaceWithTheFewestLeasedRunsBeforeAnOlderBacklog() {
        enqueue(workspace, AgentRunBand.BATCH, "review:busy-1");
        AgentRunRef older = enqueue(workspace, AgentRunBand.BATCH, "review:busy-2");
        AgentRunRef idle = enqueue(other, AgentRunBand.BATCH, "review:idle");
        assertThat(claim()).map(AgentRunLease::workspaceId).contains(workspace);

        assertThat(claim())
                .map(AgentRunLease::runId)
                .as("the idle workspace first")
                .contains(idle.id());
        assertThat(claim()).map(AgentRunLease::runId).contains(older.id());
    }

    @Test
    void shouldLeaseOneRunOfASubjectAtATimeInTheOrderTheyWereQueued() {
        AgentRunRef first = enqueue(workspace, AgentRunBand.INTERACTIVE, "conversation:one");
        AgentRunRef second = enqueue(workspace, AgentRunBand.INTERACTIVE, "conversation:one");

        AgentRunLease lease = claim().orElseThrow();
        assertThat(lease.runId()).isEqualTo(first.id());
        assertThat(claim())
                .as("the subject's next run waits for the leased one")
                .isEmpty();

        transactions.executeWithoutResult(status -> assertThat(runs.end(lease.fence(), AgentRunStopReason.COMPLETED))
                .isEqualTo(AgentRuns.EndOutcome.ENDED));
        assertThat(claim()).map(AgentRunLease::runId).contains(second.id());
    }

    @Test
    void shouldOfferAHeldRunOnlyOnceItsOwnHoldIsReleasedAndClearTheHoldAtTheClaim() {
        AgentRunRef run = enqueue(workspace, AgentRunBand.BATCH, "review:held");
        assertThat(transactions.<Boolean>execute(status -> runs.hold(run, AgentRunHold.BUDGET, Duration.ofHours(1))))
                .isTrue();

        assertThat(claim()).isEmpty();
        assertThat(holdOf(run)).isEqualTo("BUDGET");
        assertThat(transactions.<Integer>execute(
                        status -> runs.releaseHolds(run.workspaceId(), AgentRunHold.SANDBOX_ROOM)))
                .as("another reason's release leaves the hold")
                .isZero();
        assertThat(transactions.<Integer>execute(status -> runs.releaseHolds(run.workspaceId(), AgentRunHold.BUDGET)))
                .isOne();
        assertThat(claim()).map(AgentRunLease::runId).contains(run.id());
        assertThat(holdOf(run)).as("the claim ends the hold").isNull();
    }

    @Test
    void shouldKeepABackoffThatIsNoHoldWhenHoldsAreReleased() {
        AgentRunRef run = enqueue(workspace, AgentRunBand.BATCH, "review:backoff");
        AgentRunLease lease = claim().orElseThrow();
        assertThat(transactions.<Boolean>execute(
                        status -> runs.requeue(lease.fence(), AgentRuns.Charge.SPEND, Duration.ofHours(1), null)))
                .isTrue();

        assertThat(transactions.<Integer>execute(status -> runs.releaseHolds(run.workspaceId(), AgentRunHold.BUDGET)))
                .isZero();
        assertThat(claim()).as("a retry's backoff stays").isEmpty();
    }

    @Test
    void shouldRefuseAHoldOnARunThatIsNotQueued() {
        AgentRunRef run = enqueue(workspace, AgentRunBand.BATCH, "review:leased-hold");
        assertThat(claim()).map(AgentRunLease::runId).contains(run.id());

        assertThatThrownBy(() -> jdbc.update("UPDATE agent_run SET hold_reason='BUDGET' WHERE id=?", run.id()))
                .hasMessageContaining("ck_agent_run_hold_queued");
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE agent_run SET hold_reason='LATER' WHERE id=?",
                        enqueue(workspace, AgentRunBand.BATCH, "review:unknown-hold")
                                .id()))
                .hasMessageContaining("ck_agent_run_hold_reason");
    }

    private @Nullable String holdOf(AgentRunRef run) {
        return jdbc.queryForObject("SELECT hold_reason FROM agent_run WHERE id=?", String.class, run.id());
    }

    @Test
    void shouldOfferARunOfASuspendedWorkspaceToNoClaim() {
        enqueue(workspace, AgentRunBand.BATCH, "review:suspended");
        jdbc.update("UPDATE workspace SET status='SUSPENDED' WHERE id=?", workspace);

        assertThat(claim()).isEmpty();
    }

    @Test
    void shouldTakeTheNextEpochAtEachClaimAndCountOnlyChargedRequeues() {
        AgentRunRef run = enqueue(workspace, AgentRunBand.BATCH, "review:epochs");

        AgentRunLease first = claim().orElseThrow();
        assertThat(first.epoch()).isEqualTo(1);
        assertThat(first.attempt()).isZero();
        transactions.executeWithoutResult(
                status -> assertThat(runs.requeue(first.fence(), AgentRuns.Charge.FREE, Duration.ZERO, null))
                        .isTrue());

        AgentRunLease second = claim().orElseThrow();
        assertThat(second.epoch()).isEqualTo(2);
        assertThat(second.attempt()).as("a free requeue spends nothing").isZero();
        transactions.executeWithoutResult(
                status -> assertThat(runs.requeue(second.fence(), AgentRuns.Charge.SPEND, Duration.ZERO, null))
                        .isTrue());

        AgentRunLease third = claim().orElseThrow();
        assertThat(third.epoch()).isEqualTo(3);
        assertThat(third.attempt()).isEqualTo(1);
        assertThat(third.runId()).isEqualTo(run.id());
    }

    @Test
    void shouldRefuseEveryWriteOfAStaleClaim() {
        enqueue(workspace, AgentRunBand.BATCH, "review:stale");
        AgentRunLease first = claim().orElseThrow();
        transactions.executeWithoutResult(
                status -> runs.requeue(first.fence(), AgentRuns.Charge.FREE, Duration.ZERO, null));
        AgentRunLease current = claim().orElseThrow();
        AgentRunFence stale = first.fence();

        transactions.executeWithoutResult(status -> {
            assertThat(runs.renew(stale)).isEmpty();
            assertThat(runs.requeue(stale, AgentRuns.Charge.FREE, Duration.ZERO, null))
                    .isFalse();
            assertThat(runs.end(stale, AgentRunStopReason.COMPLETED)).isEqualTo(AgentRuns.EndOutcome.STALE);
            assertThat(runs.renew(new AgentRunFence(current.runId(), other, current.epoch())))
                    .as("another workspace")
                    .isEmpty();
            assertThat(runs.renew(current.fence())).isPresent();
            runs.requireCurrent(current.fence());
        });
        // The refusal rolls back the transaction that asked.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> runs.requireCurrent(stale)))
                .isInstanceOf(StaleRunException.class);
        assertThat(runs.isCurrent(current.fence().run(), current.epoch())).isTrue();
        assertThat(runs.isCurrent(stale.run(), stale.epoch())).isFalse();
    }

    @Test
    void shouldNeverReviveALeaseThatExpired() {
        enqueue(workspace, AgentRunBand.BATCH, "review:expired");
        AgentRunLease lease = claim().orElseThrow();
        expire(lease.runId());

        transactions.executeWithoutResult(
                status -> assertThat(runs.renew(lease.fence())).isEmpty());
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> runs.requireCurrent(lease.fence())))
                .isInstanceOf(StaleRunException.class);
    }

    @Test
    void shouldEndARunForGoodAndOfferItToNoClaim() {
        AgentRunRef run = enqueue(workspace, AgentRunBand.BATCH, "review:cancel");
        transactions.executeWithoutResult(status -> assertThat(runs.cancel(run)).isTrue());

        assertThat(claim()).isEmpty();
        assertThat(state(run.id())).isEqualTo("ENDED");
        assertThat(stopReason(run.id())).isEqualTo("CANCELLED");
        transactions.executeWithoutResult(status -> assertThat(runs.cancel(run)).isFalse());
    }

    @Test
    void shouldRefuseEveryTransitionThatTheStateTableRefuses() {
        AgentRunRef run = enqueue(workspace, AgentRunBand.BATCH, "review:guard");
        AgentRunLease lease = claim().orElseThrow();

        assertRefused("UPDATE agent_run SET lease_epoch = lease_epoch + 1 WHERE id = ?", run.id());
        assertRefused("UPDATE agent_run SET attempt = attempt + 1 WHERE id = ?", run.id());
        assertRefused("UPDATE agent_run SET concurrency_key = 'review:other' WHERE id = ?", run.id());
        assertRefused("UPDATE agent_run SET state = 'QUEUED' WHERE id = ?", run.id());
        transactions.executeWithoutResult(status -> runs.end(lease.fence(), AgentRunStopReason.COMPLETED));
        assertRefused(
                "UPDATE agent_run SET state = 'QUEUED', stop_reason = NULL, ended_at = NULL WHERE id = ?", run.id());
        assertRefused("UPDATE agent_run SET available_at = now() WHERE id = ?", run.id());
    }

    @Test
    void shouldRefuseASecondLeasedRunOfOneSubject() {
        AgentRunRef first = enqueue(workspace, AgentRunBand.BATCH, "review:twice");
        AgentRunRef second = enqueue(workspace, AgentRunBand.BATCH, "review:twice");
        assertThat(claim()).map(AgentRunLease::runId).contains(first.id());

        assertRefused(
                "UPDATE agent_run SET state = 'LEASED', lease_epoch = 1, started_at = now(),"
                        + " leased_until = now() + interval '1 minute' WHERE id = ?",
                second.id());
        assertThat(state(first.id())).isEqualTo("LEASED");
    }

    @Test
    void shouldContinueTheEpochsAndSpentAttemptsOfAnEndedRunOnTheRunThatGoesOn() {
        AgentRunRef run = enqueue(workspace, AgentRunBand.BATCH, "review:continued");
        AgentRunLease lease = claim().orElseThrow();
        transactions.executeWithoutResult(status -> runs.end(lease.fence(), AgentRunStopReason.COMPLETED));

        Duration window = Duration.ofMinutes(11);
        AgentRunRef next = Objects.requireNonNull(transactions.execute(status -> {
            AgentRunRef continued = runs.continueAfter(run, lease.epoch(), Duration.ZERO, window);
            runs.retire(run);
            return continued;
        }));

        AgentRunLease continued = claim().orElseThrow();
        assertThat(continued.deadline())
                .isEqualTo(runs.find(next).orElseThrow().getAvailableAt().plus(window));
        assertThat(continued.epoch()).isEqualTo(lease.epoch() + 1);
        assertThat(continued.attempt())
                .as("the continuation spends one attempt")
                .isEqualTo(lease.attempt() + 1);
        assertThat(state(run.id())).as("the ended run is retired").isNull();
        assertThat(state(next.id())).isEqualTo("LEASED");
    }

    @Test
    void shouldContinueAnEndedRunAsTheNextStepOfItsWorkWithoutSpendingAnAttempt() {
        AgentRunRef run = enqueue(workspace, AgentRunBand.INTERACTIVE, "review:composed");
        AgentRunLease measured = claim().orElseThrow();
        transactions.executeWithoutResult(status -> runs.end(measured.fence(), AgentRunStopReason.COMPLETED));

        Duration window = Duration.ofMinutes(11);
        Objects.requireNonNull(transactions.execute(
                status -> runs.continueAs(run, measured.epoch(), AgentRunKind.REVIEW_COMPOSE, window)));

        assertThat(claim())
                .as("the measuring kind's claim does not offer the composition")
                .isEmpty();
        AgentRunLease composing = Objects.requireNonNull(transactions.execute(
                        status -> runs.claimSubject(workspace, "review:composed", Set.of(AgentRunKind.REVIEW_COMPOSE))))
                .orElseThrow();
        assertThat(composing.deadline())
                .isEqualTo(runs.find(composing.fence().run())
                        .orElseThrow()
                        .getAvailableAt()
                        .plus(window));
        assertThat(composing.kind()).isEqualTo(AgentRunKind.REVIEW_COMPOSE);
        assertThat(composing.epoch()).isEqualTo(measured.epoch() + 1);
        assertThat(composing.attempt()).as("the next step is no retry").isEqualTo(measured.attempt());
        assertThat(jdbc.queryForObject("SELECT band FROM agent_run WHERE id = ?", String.class, composing.runId()))
                .isEqualTo("INTERACTIVE");
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                        status -> runs.continueAs(run, measured.epoch(), AgentRunKind.REVIEW, window)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldKeepTheComposeDeadlineWhenAFreeRequeueRestoresALostLocalDecision() {
        AgentRunRef measured = enqueue(workspace, AgentRunBand.BATCH, "review:compose-reclaimed");
        var first = claim().orElseThrow();
        AgentRunRef composing = Objects.requireNonNull(transactions.execute(tx -> {
            runs.end(first.fence(), AgentRunStopReason.COMPLETED);
            return runs.continueAs(measured, first.epoch(), AgentRunKind.REVIEW_COMPOSE, Duration.ofMinutes(11));
        }));
        var leased = Objects.requireNonNull(transactions.execute(tx ->
                        runs.claimSubject(workspace, "review:compose-reclaimed", Set.of(AgentRunKind.REVIEW_COMPOSE))))
                .orElseThrow();
        var deadline = leased.deadline();
        transactions.executeWithoutResult(
                tx -> assertThat(runs.requeue(leased.fence(), AgentRuns.Charge.FREE, Duration.ZERO, null))
                        .isTrue());
        var reclaimed = Objects.requireNonNull(transactions.execute(tx ->
                        runs.claimSubject(workspace, "review:compose-reclaimed", Set.of(AgentRunKind.REVIEW_COMPOSE))))
                .orElseThrow();
        assertThat(reclaimed.runId()).isEqualTo(composing.id());
        assertThat(reclaimed.deadline()).isNotNull().isEqualTo(deadline);
        assertThat(reclaimed.attempt()).isEqualTo(leased.attempt());
        assertThat(reclaimed.epoch()).isEqualTo(leased.epoch() + 1);
    }

    @Test
    void shouldContinueAnExpiredRunOnceWhenTwoReapersCompete() throws Exception {
        AgentRunRef old = enqueue(workspace, AgentRunBand.BATCH, "review:competing-continuation");
        AgentRunLease lease = claim().orElseThrow();
        Instant originalDeadline = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        transactions.executeWithoutResult(
                tx -> assertThat(runs.limit(lease.fence(), originalDeadline)).isTrue());
        UUID call = openCall(lease);
        expire(old.id());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var window = Duration.ofMinutes(11);
        var delay = Duration.ofMinutes(12);
        var outcomes = new ConcurrentLinkedQueue<AgentRunRef>();
        var meters = new SimpleMeterRegistry();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                futures.add(workers.submit(() -> {
                    var handler = new RecordingHandler(5) {
                        @Override
                        public void lock(AgentRunRef run) {
                            ready.countDown();
                            try {
                                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            }
                        }

                        @Override
                        public LossOutcome lost(AgentRunFence fence) {
                            var next = runs.continueExpired(fence, delay, window);
                            outcomes.add(next);
                            return new Continued(next);
                        }
                    };
                    new AgentRunReaper(repository, transactions, List.of(handler), meters).reap();
                }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertThat(meters.counter(AgentMetrics.AGENT_RUN_REAPER_FAILURE).count())
                .isZero();
        assertThat(meters.counter(AgentMetrics.AGENT_RUN_REAPED, "kind", "review", "outcome", "continued")
                        .count())
                .isEqualTo(1);
        assertThat(outcomes).singleElement().satisfies(next -> {
            var successor = runs.find(next).orElseThrow();
            assertThat(successor.getAttempt()).isEqualTo(lease.attempt() + 1);
            assertThat(successor.getLeaseEpoch()).isEqualTo(lease.epoch());
            assertThat(successor.getAvailableAt())
                    .isEqualTo(successor.getCreatedAt().plus(delay));
            assertThat(successor.getDeadline())
                    .isEqualTo(successor.getAvailableAt().plus(window));
            assertThat(successor.getKind()).isEqualTo(lease.kind());
            assertThat(successor.getBand()).isEqualTo(AgentRunBand.BATCH);
            assertThat(successor.getConcurrencyKey()).isEqualTo("review:competing-continuation");
        });
        assertThat(state(old.id())).isEqualTo("ENDED");
        assertThat(stopReason(old.id())).isEqualTo("CANCELLED");
        assertThat(deadline(old.id())).isEqualTo(originalDeadline);
        assertThat(attempt(old.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM agent_run WHERE workspace_id = ? AND state = 'QUEUED'",
                        Long.class,
                        workspace))
                .isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT call_state, unreported_calls FROM llm_usage_event WHERE id = ?", call))
                .containsEntry("call_state", "SETTLED")
                .containsEntry("unreported_calls", 1);
        assertThat(runs.isCurrent(old, lease.epoch())).isFalse();
        transactions.executeWithoutResult(
                tx -> assertThat(runs.renew(lease.fence())).isEmpty());
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> runs.requireCurrent(lease.fence())))
                .isInstanceOf(StaleRunException.class);
    }

    @Test
    void shouldTreatARenewalThatWinsAContinuationAsANoopWithoutAReaperFailure() {
        AgentRunRef old = enqueue(workspace, AgentRunBand.BATCH, "review:continued-renewal");
        AgentRunLease lease = claim().orElseThrow();
        UUID call = openCall(lease);
        expire(old.id());
        var renewal = new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
        renewal.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var handler = new RecordingHandler(5) {
            @Override
            public LossOutcome lost(AgentRunFence fence) {
                renewal.executeWithoutResult(tx -> jdbc.update(
                        "UPDATE agent_run SET leased_until = now() + interval '1 minute' WHERE id = ?", fence.runId()));
                return new Continued(runs.continueExpired(fence, Duration.ZERO, Duration.ofMinutes(11)));
            }
        };
        var meters = new SimpleMeterRegistry();
        try (var logs = new LogCapture(AgentRunReaper.class)) {
            new AgentRunReaper(repository, transactions, List.of(handler), meters).reap();
            assertThat(logs.events("agent.run.reaper.failed")).isEmpty();
            assertThat(logs.events("agent.run.continued")).isEmpty();
        }
        assertThat(meters.counter(AgentMetrics.AGENT_RUN_REAPER_FAILURE).count())
                .isZero();
        assertThat(state(old.id())).isEqualTo("LEASED");
        assertThat(attempt(old.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE workspace_id = ?", Long.class, workspace))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT call_state FROM llm_usage_event WHERE id = ?", String.class, call))
                .isEqualTo("OPEN");
    }

    @Test
    void shouldRollBackTheSuccessorAndSettlementWhenDomainRelinkFailsAndKeepTheFailureSignal() {
        AgentRunRef old = enqueue(workspace, AgentRunBand.BATCH, "review:continued-rollback");
        AgentRunLease lease = claim().orElseThrow();
        UUID call = openCall(lease);
        expire(old.id());
        var handler = new RecordingHandler(5) {
            @Override
            public LossOutcome lost(AgentRunFence fence) {
                runs.continueExpired(fence, Duration.ZERO, Duration.ofMinutes(11));
                throw new IllegalStateException("domain relink refused");
            }
        };
        var meters = new SimpleMeterRegistry();
        new AgentRunReaper(repository, transactions, List.of(handler), meters).reap();
        assertThat(meters.counter(AgentMetrics.AGENT_RUN_REAPER_FAILURE).count())
                .isEqualTo(1);
        assertThat(meters.counter(AgentMetrics.AGENT_RUN_REAPED, "kind", "review", "outcome", "continued")
                        .count())
                .isZero();
        assertThat(state(old.id())).isEqualTo("LEASED");
        assertThat(attempt(old.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE workspace_id = ?", Long.class, workspace))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT call_state FROM llm_usage_event WHERE id = ?", String.class, call))
                .isEqualTo("OPEN");
    }

    @Test
    void shouldSignalAnExpiredCompositionWithNoDomainInsteadOfHidingItAsAStaleFence() {
        AgentRunRef orphan = Objects.requireNonNull(transactions.execute(tx -> runs.enqueue(
                workspace, AgentRunKind.REVIEW_COMPOSE, AgentRunBand.BATCH, "review:missing-domain", null)));
        var lease = Objects.requireNonNull(transactions.execute(tx ->
                        runs.claimSubject(workspace, "review:missing-domain", Set.of(AgentRunKind.REVIEW_COMPOSE))))
                .orElseThrow();
        UUID call = openCall(lease);
        expire(orphan.id());
        var meters = new SimpleMeterRegistry();
        try (var logs = new LogCapture(AgentRunReaper.class)) {
            new AgentRunReaper(repository, transactions, List.of(composeKind), meters).reap();
            assertThat(logs.events("agent.run.reaper.failed"))
                    .singleElement()
                    .satisfies(fields -> assertThat(fields)
                            .containsEntry("run.id", orphan.id().toString())
                            .containsEntry("error.type", IllegalStateException.class.getName()));
        }
        assertThat(meters.counter(AgentMetrics.AGENT_RUN_REAPER_FAILURE).count())
                .isEqualTo(1);
        assertThat(state(orphan.id())).isEqualTo("LEASED");
        assertThat(attempt(orphan.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE workspace_id = ?", Long.class, workspace))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT call_state FROM llm_usage_event WHERE id = ?", String.class, call))
                .isEqualTo("OPEN");
    }

    private UUID openCall(AgentRunLease lease) {
        return Objects.requireNonNull(transactions.execute(tx -> usage.open(new LlmUsageRecorder.OpenCall(
                workspace,
                lease.runId(),
                lease.epoch(),
                "PRACTICE_REVIEW",
                UsageSourceType.REVIEW_JOB,
                UUID.randomUUID(),
                lease.epoch() - 1,
                null,
                ModelKind.CHAT,
                null,
                "compose-test",
                new LlmPriceSnapshot(
                        UsageFundingSource.INSTANCE, UsagePricingState.UNPRICED, null, null, null, null, null, null),
                null))));
    }

    @Test
    void shouldListTheRunsThatTheClaimOffersNextInItsOrderWithoutLeasingThem() {
        AgentRunRef batch = enqueue(workspace, AgentRunBand.BATCH, "review:due-batch");
        AgentRunRef interactive = enqueue(workspace, AgentRunBand.INTERACTIVE, "review:due-interactive");
        enqueue(workspace, AgentRunBand.BATCH, "review:due-batch");

        var due = runs.due(Set.of(AgentRunKind.REVIEW), 10);

        assertThat(due)
                .extracting(AgentRuns.DueRun::run)
                .as("the interactive run first, and a subject's younger run not at all")
                .containsExactly(interactive, batch);
        assertThat(runs.due(Set.of(AgentRunKind.REVIEW_COMPOSE), 10)).isEmpty();
        assertThat(runs.due(Set.of(AgentRunKind.REVIEW), 1)).hasSize(1);
        assertThat(state(batch.id())).isEqualTo("QUEUED");
    }

    @Test
    void shouldRequeueAnExpiredLeaseWithItsDomainRowAndLeaveARenewedOneAlone() {
        var handler = new RecordingHandler(2);
        var reaper = reaper(handler);
        enqueue(workspace, AgentRunBand.BATCH, "review:lost");
        enqueue(workspace, AgentRunBand.BATCH, "review:live");
        AgentRunLease lost = claim().orElseThrow();
        AgentRunLease live = claim().orElseThrow();
        expire(lost.runId());

        try (var logs = new LogCapture(AgentRunReaper.class)) {
            reaper.reap();

            assertThat(logs.events("agent.run.requeued"))
                    .as("the event names the run as the records of its holder do")
                    .singleElement()
                    .satisfies(fields -> assertThat(fields)
                            .containsEntry("run.id", lost.runId().toString())
                            .containsEntry("run.epoch", Integer.toString(lost.epoch()))
                            .containsEntry("subject.key", "review:lost")
                            .containsEntry("workspace.id", Long.toString(workspace))
                            .containsEntry("run.kind", "REVIEW"));
        }
        assertThat(handler.requeued).containsExactly(lost.fence());
        assertThat(handler.ended).isEmpty();
        assertThat(state(lost.runId())).isEqualTo("QUEUED");
        assertThat(attempt(lost.runId())).as("a lost lease spends an attempt").isEqualTo(1);
        assertThat(state(live.runId())).isEqualTo("LEASED");
        assertThat(handler.lockedBeforeWrite)
                .as("the domain row is locked before the run is written")
                .isTrue();
        transactions.executeWithoutResult(status -> assertThat(runs.renew(lost.fence()))
                .as("the lost holder cannot renew")
                .isEmpty());
    }

    @Test
    void shouldEndARunWhoseLeasesWereLostMoreOftenThanItsKindAllows() {
        var handler = new RecordingHandler(1);
        var reaper = reaper(handler);
        AgentRunRef run = enqueue(workspace, AgentRunBand.BATCH, "review:exhausted");
        expire(claim().orElseThrow().runId());
        reaper.reap();
        jdbc.update("UPDATE agent_run SET available_at = now() WHERE id = ?", run.id());
        AgentRunLease last = claim().orElseThrow();
        expire(last.runId());

        reaper.reap();

        assertThat(handler.ended).containsExactly(run.id() + ":" + last.epoch() + ":ATTEMPTS_EXHAUSTED");
        assertThat(state(run.id())).isEqualTo("ENDED");
        assertThat(stopReason(run.id())).isEqualTo("ATTEMPTS_EXHAUSTED");
    }

    @Test
    void shouldEndTheRunWhenItsKindEndedTheDomainRowInsteadOfQueuingItAgain() {
        var handler = new RecordingHandler(5) {
            @Override
            public LossOutcome lost(AgentRunFence fence) {
                super.lost(fence);
                return new End();
            }
        };
        var reaper = reaper(handler);
        enqueue(workspace, AgentRunBand.BATCH, "review:kind-budget");
        AgentRunLease lease = claim().orElseThrow();
        expire(lease.runId());

        reaper.reap();

        assertThat(state(lease.runId())).isEqualTo("ENDED");
        assertThat(stopReason(lease.runId())).isEqualTo("ATTEMPTS_EXHAUSTED");
    }

    @Test
    void shouldRollTheDomainBackWhenTheLeaseWasRenewedBeforeTheReaperWroteTheRun() {
        var renewing = new RecordingHandler(5) {
            @Override
            public LossOutcome lost(AgentRunFence fence) {
                // The holder renews between the reaper's read and its write.
                jdbc.update(
                        "UPDATE agent_run SET leased_until = now() + interval '1 minute' WHERE id = ?", fence.runId());
                return super.lost(fence);
            }
        };
        enqueue(workspace, AgentRunBand.BATCH, "review:renewed");
        AgentRunLease lease = claim().orElseThrow();
        expire(lease.runId());

        reaper(renewing).reap();

        assertThat(state(lease.runId()))
                .as("the renewal rolled back with the reaper's transaction")
                .isEqualTo("LEASED");
    }

    @Test
    void shouldTakeBackTheRunsBehindARunWhoseTakeBackFails() {
        enqueue(workspace, AgentRunBand.BATCH, "review:failing");
        enqueue(workspace, AgentRunBand.BATCH, "review:behind");
        AgentRunLease failing = claim().orElseThrow();
        AgentRunLease behind = claim().orElseThrow();
        // The failing lease expired first, so the reaper meets it first.
        jdbc.update("UPDATE agent_run SET leased_until = now() - interval '2 seconds' WHERE id = ?", failing.runId());
        expire(behind.runId());
        var handler = new RecordingHandler(5) {
            @Override
            public LossOutcome lost(AgentRunFence fence) {
                if (fence.runId().equals(failing.runId())) throw new IllegalStateException("the domain row is broken");
                return super.lost(fence);
            }
        };

        try (var logs = new LogCapture(AgentRunReaper.class)) {
            reaper(handler).reap();

            assertThat(logs.events("agent.run.reaper.failed"))
                    .singleElement()
                    .satisfies(fields -> assertThat(fields)
                            .containsEntry("run.id", failing.runId().toString())
                            .containsEntry("subject.key", "review:failing")
                            .containsEntry("error.type", IllegalStateException.class.getName()));
        }
        assertThat(state(failing.runId())).as("tried again next tick").isEqualTo("LEASED");
        assertThat(state(behind.runId())).isEqualTo("QUEUED");
        assertThat(handler.requeued).contains(behind.fence()).doesNotContain(failing.fence());
    }

    @Test
    void shouldEndARunThatWaitedForRoomPastItsStartWindowButKeepOneThatWaitsBehindItsSubject() {
        var handler = new RecordingHandler(5);
        var reaper = reaper(handler);
        enqueue(workspace, AgentRunBand.BATCH, "review:window-held");
        assertThat(claimSubject(workspace, "review:window-held")).isPresent();
        AgentRunRef behind = enqueueWithin(workspace, "review:window-held", Duration.ofSeconds(1));
        AgentRunRef waited = enqueueWithin(workspace, "review:window-free", Duration.ofSeconds(1));
        sleep(Duration.ofMillis(1100));

        try (var logs = new LogCapture(AgentRunReaper.class)) {
            reaper.reap();

            assertThat(logs.events("agent.run.ended"))
                    .anySatisfy(fields -> assertThat(fields)
                            .containsEntry("run.id", waited.id().toString())
                            .containsEntry("run.stop_reason", "NOT_STARTED"));
        }
        assertThat(stopReason(waited.id())).isEqualTo("NOT_STARTED");
        assertThat(handler.ended).contains(waited.id() + ":0:NOT_STARTED");
        assertThat(state(behind.id()))
                .as("it waits for its subject, not for room")
                .isEqualTo("QUEUED");
    }

    @Test
    void shouldGiveARequeuedRunItsStartWindowAgain() {
        var reaper = reaper(new RecordingHandler(5));
        AgentRunRef run = enqueueWithin(workspace, "review:window-again", Duration.ofSeconds(2));
        AgentRunLease lease = claimSubject(workspace, "review:window-again").orElseThrow();
        sleep(Duration.ofMillis(2100));
        transactions.executeWithoutResult(
                status -> assertThat(runs.requeue(lease.fence(), AgentRuns.Charge.FREE, Duration.ZERO, null))
                        .isTrue());

        reaper.reap();

        assertThat(state(run.id())).as("due again just now").isEqualTo("QUEUED");
    }

    @Test
    void shouldLeaseNoRunOfACappedKindBeyondItsCap() {
        String conversation = "conversation:" + UUID.randomUUID();
        AgentRunRef first = enqueueTurn(workspace, conversation + "-a");
        AgentRunRef second = enqueueTurn(workspace, conversation + "-b");
        int leased = countLeasedTurns();

        AgentRunLease taken = claimCapped(leased + 1).orElseThrow();
        assertThat(List.of(first.id(), second.id())).contains(taken.runId());
        assertThat(claimCapped(leased + 1)).as("the cap is taken").isEmpty();
        transactions.executeWithoutResult(status -> assertThat(runs.end(taken.fence(), AgentRunStopReason.COMPLETED))
                .isEqualTo(AgentRuns.EndOutcome.ENDED));
        assertThat(claimCapped(leased + 1)).isPresent();
    }

    @Test
    void shouldLeaseATurnForTheShorterTurnLease() {
        String subject = "conversation:" + UUID.randomUUID();
        enqueueTurn(workspace, subject);

        AgentRunLease lease = Objects.requireNonNull(transactions.execute(
                        status -> runs.claimSubject(workspace, subject, Set.of(AgentRunKind.TURN))))
                .orElseThrow();

        assertThat(Duration.between(Instant.now(), lease.leasedUntil()))
                .isLessThanOrEqualTo(runs.capacity().turnLeaseTtl())
                .isLessThan(runs.capacity().leaseTtl());
    }

    @Test
    void shouldMoveADeadlineOnlyEarlierAndClearItWhenTheRunIsRequeued() {
        AgentRunRef run = enqueue(workspace, AgentRunBand.BATCH, "review:deadline-moves");
        AgentRunLease lease = claimSubject(workspace, "review:deadline-moves").orElseThrow();
        Instant early = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);

        transactions.executeWithoutResult(status -> {
            assertThat(runs.endBy(run, early)).isTrue();
            assertThat(runs.endBy(run, early.plusSeconds(60))).isTrue();
        });
        assertThat(deadline(run.id())).as("a later deadline keeps the earlier").isEqualTo(early);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE agent_run SET deadline = deadline + interval '1 hour' WHERE id = ?", run.id()))
                .hasMessageContaining("only moves earlier");
        transactions.executeWithoutResult(
                status -> assertThat(runs.requeue(lease.fence(), AgentRuns.Charge.FREE, Duration.ZERO, null))
                        .isTrue());
        assertThat(deadline(run.id())).as("the next claim sets its own").isNull();
    }

    @Test
    void shouldEndALeasedRunAtItsDeadlineAlthoughItsLeaseIsLive() {
        var handler = new RecordingHandler(5);
        var reaper = reaper(handler);
        AgentRunRef run = enqueue(workspace, AgentRunBand.BATCH, "review:overran");
        AgentRunLease lease = claimSubject(workspace, "review:overran").orElseThrow();
        transactions.executeWithoutResult(
                status -> runs.endBy(run, Instant.now().minusSeconds(1)));

        transactions.executeWithoutResult(status -> assertThat(runs.renew(lease.fence()))
                .as("no renewal past the deadline")
                .isEmpty());
        reaper.reap();

        assertThat(stopReason(run.id())).isEqualTo("DEADLINE");
        assertThat(handler.ended).containsExactly(run.id() + ":" + lease.epoch() + ":DEADLINE");
    }

    @Test
    void shouldTellAHolderWhetherItsEndEndedTheRunRepeatedAnEndOrCameTooLate() {
        enqueue(workspace, AgentRunBand.BATCH, "review:ends");
        AgentRunLease lease = claimSubject(workspace, "review:ends").orElseThrow();

        transactions.executeWithoutResult(status -> {
            assertThat(runs.end(lease.fence(), AgentRunStopReason.COMPLETED)).isEqualTo(AgentRuns.EndOutcome.ENDED);
            assertThat(runs.end(lease.fence(), AgentRunStopReason.COMPLETED))
                    .isEqualTo(AgentRuns.EndOutcome.ALREADY_ENDED);
            assertThat(runs.end(
                            new AgentRunFence(lease.runId(), lease.workspaceId(), lease.epoch() + 1),
                            AgentRunStopReason.COMPLETED))
                    .isEqualTo(AgentRuns.EndOutcome.STALE);
        });
    }

    private AgentRunRef enqueueWithin(long workspaceId, String subject, Duration startWithin) {
        AgentRunRef run = Objects.requireNonNull(transactions.execute(
                status -> runs.enqueue(workspaceId, AgentRunKind.REVIEW, AgentRunBand.BATCH, subject, startWithin)));
        sleep();
        return run;
    }

    private AgentRunRef enqueueTurn(long workspaceId, String subject) {
        AgentRunRef run = Objects.requireNonNull(transactions.execute(
                status -> runs.enqueue(workspaceId, AgentRunKind.TURN, AgentRunBand.INTERACTIVE, subject, null)));
        sleep();
        return run;
    }

    private Optional<AgentRunLease> claimCapped(int cap) {
        return Objects.requireNonNull(transactions.execute(status -> runs.claimCapped(AgentRunKind.TURN, cap)));
    }

    private int countLeasedTurns() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM agent_run WHERE kind = 'TURN' AND state = 'LEASED'", Integer.class));
    }

    private @Nullable Instant deadline(UUID runId) {
        Timestamp at = jdbc.queryForObject("SELECT deadline FROM agent_run WHERE id = ?", Timestamp.class, runId);
        return at == null ? null : at.toInstant();
    }

    private Optional<AgentRunLease> claim() {
        return Objects.requireNonNull(
                transactions.execute(status -> runs.claim(Set.of(AgentRunKind.REVIEW), List.of())));
    }

    private Optional<AgentRunLease> claimSubject(long workspaceId, String subject) {
        return Objects.requireNonNull(
                transactions.execute(status -> runs.claimSubject(workspaceId, subject, Set.of(AgentRunKind.REVIEW))));
    }

    private AgentRunRef enqueue(long workspaceId, AgentRunBand band, String subject) {
        AgentRunRef run = Objects.requireNonNull(
                transactions.execute(status -> runs.enqueue(workspaceId, AgentRunKind.REVIEW, band, subject, null)));
        // Creation times of runs queued within one clock tick could tie; each run is a little later.
        sleep();
        return run;
    }

    private static void sleep() {
        sleep(Duration.ofMillis(2));
    }

    private static void sleep(Duration wait) {
        try {
            Thread.sleep(wait);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void expire(UUID runId) {
        jdbc.update("UPDATE agent_run SET leased_until = now() - interval '1 second' WHERE id = ?", runId);
    }

    private @Nullable String state(UUID runId) {
        return jdbc.query("SELECT state FROM agent_run WHERE id = ?", (rs, row) -> rs.getString(1), runId).stream()
                .findFirst()
                .orElse(null);
    }

    private @Nullable String stopReason(UUID runId) {
        return jdbc.queryForObject("SELECT stop_reason FROM agent_run WHERE id = ?", String.class, runId);
    }

    private int attempt(UUID runId) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT attempt FROM agent_run WHERE id = ?", Integer.class, runId));
    }

    private void assertRefused(String sql, UUID runId) {
        Supplier<Integer> update = () -> jdbc.update(sql, runId);
        assertThatThrownBy(update::get).as(sql).isNotNull();
    }

    private AgentRunReaper reaper(AgentRunHandler handler) {
        return new AgentRunReaper(repository, transactions, List.of(handler), new SimpleMeterRegistry());
    }

    /** A kind whose domain row is a list: it records what the reaper hands it, in order. */
    private class RecordingHandler implements AgentRunHandler {
        private final int retries;
        final List<AgentRunFence> requeued = new ArrayList<>();
        final List<String> ended = new ArrayList<>();
        boolean lockedBeforeWrite;
        private @Nullable AgentRunRef locked;

        RecordingHandler(int retries) {
            this.retries = retries;
        }

        @Override
        public AgentRunKind kind() {
            return AgentRunKind.REVIEW;
        }

        @Override
        public int retries() {
            return retries;
        }

        @Override
        public void lock(AgentRunRef run) {
            // Nothing has written the run yet when the reaper asks for the domain lock.
            lockedBeforeWrite = !"ENDED".equals(state(run.id()));
            locked = run;
        }

        @Override
        public LossOutcome lost(AgentRunFence fence) {
            assertThat(locked).isEqualTo(fence.run());
            assertThat(state(fence.runId()))
                    .as("the domain moves before the run")
                    .isEqualTo("LEASED");
            requeued.add(fence);
            return new Requeue();
        }

        @Override
        public void ended(AgentRunRef run, int lastEpoch, AgentRunStopReason reason) {
            assertThat(locked).isEqualTo(run);
            ended.add(run.id() + ":" + lastEpoch + ":" + reason);
        }
    }
}
