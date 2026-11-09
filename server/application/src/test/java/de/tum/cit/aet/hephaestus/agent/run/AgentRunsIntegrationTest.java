package de.tum.cit.aet.hephaestus.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.observability.LogCapture;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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

        transactions.executeWithoutResult(
                status -> assertThat(runs.end(lease.fence())).isTrue());
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
            assertThat(runs.end(stale)).isFalse();
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
        transactions.executeWithoutResult(status -> runs.end(lease.fence()));
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
        transactions.executeWithoutResult(status -> runs.end(lease.fence()));

        AgentRunRef next = Objects.requireNonNull(transactions.execute(status -> {
            AgentRunRef continued = runs.continueAfter(run, lease.epoch());
            runs.retire(run);
            return continued;
        }));

        AgentRunLease continued = claim().orElseThrow();
        assertThat(continued.epoch()).isEqualTo(lease.epoch() + 1);
        assertThat(continued.attempt())
                .as("the continuation spends one attempt")
                .isEqualTo(lease.attempt() + 1);
        assertThat(state(run.id())).as("the ended run is retired").isNull();
        assertThat(state(next.id())).isEqualTo("LEASED");
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
            public boolean lost(AgentRunFence fence) {
                super.lost(fence);
                return false;
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
            public boolean lost(AgentRunFence fence) {
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
            public boolean lost(AgentRunFence fence) {
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
    void shouldEndAQueuedRunWhoseDeadlinePassed() {
        var handler = new RecordingHandler(5);
        var reaper = reaper(handler);
        AgentRunRef run = Objects.requireNonNull(transactions.execute(status -> runs.enqueue(
                workspace,
                AgentRunKind.REVIEW,
                AgentRunBand.INTERACTIVE,
                "conversation:late",
                Instant.now().minusSeconds(1))));

        try (var logs = new LogCapture(AgentRunReaper.class)) {
            reaper.reap();

            assertThat(logs.events("agent.run.ended"))
                    .singleElement()
                    .satisfies(fields -> assertThat(fields)
                            .containsEntry("run.id", run.id().toString())
                            .containsEntry("subject.key", "conversation:late")
                            .containsEntry("run.stop_reason", "DEADLINE"));
        }
        assertThat(state(run.id())).isEqualTo("ENDED");
        assertThat(stopReason(run.id())).isEqualTo("DEADLINE");
        assertThat(handler.ended).containsExactly(run.id() + ":0:DEADLINE");
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
        try {
            Thread.sleep(2);
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
        public boolean lost(AgentRunFence fence) {
            assertThat(locked).isEqualTo(fence.run());
            assertThat(state(fence.runId()))
                    .as("the domain moves before the run")
                    .isEqualTo("LEASED");
            requeued.add(fence);
            return true;
        }

        @Override
        public void ended(AgentRunRef run, int lastEpoch, AgentRunStopReason reason) {
            assertThat(locked).isEqualTo(run);
            ended.add(run.id() + ":" + lastEpoch + ":" + reason);
        }
    }
}
