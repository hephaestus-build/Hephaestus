package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TurnSandboxesTest extends BaseUnitTest {

    private static final long WORKSPACE = 7;

    private final FakeExecSandboxes runtime = new FakeExecSandboxes();
    private final TurnSandboxes sandboxes = new TurnSandboxes(runtime, 2, new SimpleMeterRegistry());

    @AfterEach
    void close() {
        sandboxes.close();
    }

    private void filled() {
        sandboxes.reconcile((turnId, attempt) -> true);
        await().atMost(Duration.ofSeconds(5)).until(() -> sandboxes.pooled() == 2);
    }

    private static TurnSandboxes.Attempt attempt() {
        return new TurnSandboxes.Attempt(UUID.randomUUID(), 1);
    }

    @Test
    void shouldRemoveToolsThatStartAfterTheRequestDeadline() throws Exception {
        var expired = new AtomicBoolean();
        runtime.createGate = new CountDownLatch(1);
        try (var unpooled = new TurnSandboxes(runtime, 0, new SimpleMeterRegistry())) {
            var waiting = CompletableFuture.supplyAsync(() -> unpooled.acquire(attempt(), WORKSPACE, expired::get));
            await().atMost(Duration.ofSeconds(5)).until(() -> unpooled.held() == 1);
            expired.set(true);
            runtime.createGate.countDown();
            assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(SandboxException.class);
            await().atMost(Duration.ofSeconds(5)).until(() -> unpooled.held() == 0);
            assertThat(runtime.createdCount()).isEqualTo(1);
        } finally {
            runtime.createGate.countDown();
        }
    }

    @Test
    void shouldStopWaitingWhenTheToolsRequestDeadlineExpires() {
        var expired = new AtomicBoolean();
        try (var unpooled = new TurnSandboxes(runtime, 0, new SimpleMeterRegistry())) {
            runtime.quotaFull = true;
            var attempt = attempt();
            var waiting = CompletableFuture.supplyAsync(() -> unpooled.acquire(attempt, WORKSPACE, expired::get));
            await().atMost(Duration.ofSeconds(5)).until(() -> unpooled.waitingForQuota(attempt));
            expired.set(true);
            assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(SandboxException.class);
            assertThat(runtime.createdCount()).isZero();
            assertThat(unpooled.waitingForQuota(attempt)).isFalse();
        }
    }

    @Test
    void shouldWaitForToolsQuotaWithoutCreatingAPodOrDisablingTheWorker() throws Exception {
        var meters = new SimpleMeterRegistry();
        try (var unpooled = new TurnSandboxes(runtime, 0, meters)) {
            runtime.quotaFull = true;
            var attempt = attempt();
            var waiting = CompletableFuture.supplyAsync(() -> unpooled.acquire(attempt, WORKSPACE, () -> false));
            await().atMost(Duration.ofSeconds(5)).until(() -> unpooled.waitingForQuota(attempt));
            assertThat(runtime.createdCount()).isZero();
            assertThat(unpooled.available()).isTrue();
            runtime.quotaFull = false;
            assertThat(waiting.get(5, TimeUnit.SECONDS).containerId()).isNotBlank();
            assertThat(unpooled.waitingForQuota(attempt)).isFalse();
            assertThat(meters.get(AgentMetrics.MENTOR_TURN_TOOLS_WAIT)
                            .tag("outcome", "ready")
                            .timer()
                            .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void shouldEndAQuotaWaitWithoutCreatingToolsAfterTheAttemptEnds() {
        var meters = new SimpleMeterRegistry();
        try (var unpooled = new TurnSandboxes(runtime, 0, meters)) {
            runtime.quotaFull = true;
            var attempt = attempt();
            var waiting = CompletableFuture.supplyAsync(() -> unpooled.acquire(attempt, WORKSPACE, () -> false));
            await().atMost(Duration.ofSeconds(5)).until(() -> unpooled.waitingForQuota(attempt));
            unpooled.release(attempt.turnId(), attempt.number());
            assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(SandboxException.class);
            assertThat(runtime.createdCount()).isZero();
            assertThat(unpooled.waitingForQuota(attempt)).isFalse();
            assertThat(meters.get(AgentMetrics.MENTOR_TURN_TOOLS_WAIT)
                            .tag("outcome", "abandoned")
                            .timer()
                            .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void shouldTakeAPooledSandboxAndFillThePoolAgainWhenAnAttemptAcquiresOne() {
        filled();

        TurnSandboxes.Sandbox taken = sandboxes.acquire(attempt(), WORKSPACE, () -> false);

        assertThat(taken.containerId()).isIn("container-1", "container-2");
        await().atMost(Duration.ofSeconds(5)).until(() -> sandboxes.pooled() == 2);
        assertThat(runtime.createdCount()).isEqualTo(3);
    }

    @Test
    void shouldStartASandboxAtOnceWhenThePoolIsEmpty() {
        var sandboxesWithoutPool = new TurnSandboxes(runtime, 0, new SimpleMeterRegistry());

        TurnSandboxes.Sandbox taken = sandboxesWithoutPool.acquire(attempt(), WORKSPACE, () -> false);

        assertThat(taken.containerId()).isEqualTo("container-1");
        sandboxesWithoutPool.close();
    }

    @Test
    void shouldKeepTheSameSandboxForTheAttemptAndRemoveItWithoutReturningItToThePoolWhenTheAttemptEnds() {
        filled();
        TurnSandboxes.Attempt attempt = attempt();
        TurnSandboxes.Sandbox taken = sandboxes.acquire(attempt, WORKSPACE, () -> false);
        String container = taken.containerId();
        assertThat(sandboxes.acquire(attempt, WORKSPACE, () -> false)).isSameAs(taken);

        sandboxes.release(attempt.turnId(), attempt.number());

        await().atMost(Duration.ofSeconds(5)).until(() -> runtime.removed.contains(container));
        assertThat(taken.removed()).isTrue();
        assertThatThrownBy(taken::containerId).isInstanceOf(SandboxException.class);
        assertThatThrownBy(() -> sandboxes.acquire(attempt, WORKSPACE, () -> false))
                .as("a call of the ended attempt gets no sandbox")
                .isInstanceOf(SandboxException.class);
    }

    @Test
    void shouldRemoveTheSandboxThatStartsWhenTheAttemptEndedWhileItStarted() throws Exception {
        var unpooled = new TurnSandboxes(runtime, 0, new SimpleMeterRegistry());
        CountDownLatch gate = new CountDownLatch(1);
        runtime.createGate = gate;
        TurnSandboxes.Attempt attempt = attempt();
        CompletableFuture<TurnSandboxes.Sandbox> acquiring =
                CompletableFuture.supplyAsync(() -> unpooled.acquire(attempt, WORKSPACE, () -> false));
        await().atMost(Duration.ofSeconds(5)).until(() -> unpooled.held() == 1);

        unpooled.release(attempt.turnId(), attempt.number());
        gate.countDown();

        assertThatThrownBy(acquiring::join).hasCauseInstanceOf(SandboxException.class);
        await().atMost(Duration.ofSeconds(5)).until(() -> runtime.running.isEmpty() && runtime.removed.size() == 1);
        unpooled.close();
    }

    @Test
    void shouldRemoveTheSandboxThatStartsWhenTheWorkerStopsWhileItStarts() throws Exception {
        var unpooled = new TurnSandboxes(runtime, 0, new SimpleMeterRegistry());
        CountDownLatch gate = new CountDownLatch(1);
        runtime.createGate = gate;
        CompletableFuture<TurnSandboxes.Sandbox> acquiring =
                CompletableFuture.supplyAsync(() -> unpooled.acquire(attempt(), WORKSPACE, () -> false));
        await().atMost(Duration.ofSeconds(5)).until(() -> unpooled.held() == 1);

        unpooled.close();
        gate.countDown();

        assertThatThrownBy(acquiring::join).hasCauseInstanceOf(SandboxException.class);
        await().atMost(Duration.ofSeconds(5)).until(() -> runtime.running.isEmpty() && runtime.removed.size() == 1);
    }

    @Test
    void shouldOfferNoSandboxWhenTheWorkerCannotNameItself() {
        runtime.self = null;

        sandboxes.reconcile((turnId, attempt) -> true);

        assertThat(sandboxes.available()).isFalse();
        assertThat(runtime.createdCount()).as("no pool is filled").isZero();
    }

    @Test
    void shouldFillThePoolOnlyOnceTheRuntimeIsReadyWhenItReconciles() {
        runtime.ready = false;

        sandboxes.reconcile((turnId, attempt) -> true);

        assertThat(sandboxes.available()).isFalse();
        assertThat(runtime.createdCount())
                .as("no start while the runtime is not ready")
                .isZero();

        runtime.ready = true;
        sandboxes.reconcile((turnId, attempt) -> true);

        assertThat(sandboxes.available()).isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> runtime.createdCount() == 2);
    }

    @Test
    void shouldReleaseEveryAssignedSandboxWhenChangesMayHaveBeenMissed() {
        var revocations = new TurnContextRevocations(new TurnContextGenerations(), sandboxes);
        TurnSandboxes.Attempt first = attempt();
        TurnSandboxes.Attempt second = attempt();
        String one = sandboxes.acquire(first, WORKSPACE, () -> false).containerId();
        String other = sandboxes.acquire(second, WORKSPACE + 1, () -> false).containerId();

        revocations.resync();

        await().atMost(Duration.ofSeconds(5)).until(() -> runtime.removed.containsAll(List.of(one, other)));
        assertThatThrownBy(() -> sandboxes.acquire(first, WORKSPACE, () -> false))
                .isInstanceOf(TurnSandboxes.ContextChanged.class);
    }

    @Test
    void shouldMakeANewGenerationOfOnlyTheChangedWorkspaceWhenItsContextChanges() {
        var generations = new TurnContextGenerations();
        var revocations = new TurnContextRevocations(generations, sandboxes);
        long before = generations.current(WORKSPACE);
        long other = generations.current(WORKSPACE + 1);

        revocations.contextChanged(new MentorLedgerEvents.ContextChanged(WORKSPACE));

        assertThat(generations.current(WORKSPACE)).isGreaterThan(before);
        assertThat(generations.current(WORKSPACE + 1)).isEqualTo(other);
        revocations.resync();
        assertThat(generations.current(WORKSPACE + 1)).isGreaterThan(other);
    }

    @Test
    void shouldRemoveTheSandboxOfAnAttemptThatNoLongerRunsHereWhenItReconciles() {
        TurnSandboxes.Attempt attempt = attempt();
        String container = sandboxes.acquire(attempt, WORKSPACE, () -> false).containerId();

        sandboxes.reconcile((turnId, number) -> !turnId.equals(attempt.turnId()));

        await().atMost(Duration.ofSeconds(5)).until(() -> runtime.removed.contains(container));
    }

    @Test
    void shouldRemoveUnknownOwnAndAbandonedSandboxesButKeepThoseOfALiveWorkerWhenItReconciles() {
        runtime.running.put("left-by-an-earlier-start", "unknown-sandbox");
        runtime.others.add(new ExecSandboxes.Listed("of-a-live-worker", "s-1", "worker-live", false));
        runtime.others.add(new ExecSandboxes.Listed("of-a-gone-worker", "s-2", "worker-gone", true));

        sandboxes.reconcile((turnId, number) -> true);

        assertThat(runtime.removed).contains("left-by-an-earlier-start", "of-a-gone-worker");
        assertThat(runtime.removed).doesNotContain("of-a-live-worker");
        await().atMost(Duration.ofSeconds(5)).until(() -> sandboxes.pooled() == 2);
        sandboxes.reconcile((turnId, number) -> true);
        assertThat(runtime.removed).as("its own pool stays").hasSize(2);
    }

    @Test
    void shouldSayOnceThatTheContextChangedThenStartOverWhenTheWorkspaceDataChanges() {
        TurnSandboxes.Attempt attempt = attempt();
        TurnSandboxes.Attempt otherWorkspace = attempt();
        String container = sandboxes.acquire(attempt, WORKSPACE, () -> false).containerId();
        String kept =
                sandboxes.acquire(otherWorkspace, WORKSPACE + 1, () -> false).containerId();

        sandboxes.releaseWorkspace(WORKSPACE);

        assertThatThrownBy(() -> sandboxes.acquire(attempt, WORKSPACE, () -> false))
                .isInstanceOf(TurnSandboxes.ContextChanged.class);
        assertThat(sandboxes.acquire(attempt, WORKSPACE, () -> false).containerId())
                .isNotEqualTo(container);
        assertThat(sandboxes.acquire(otherWorkspace, WORKSPACE + 1, () -> false).containerId())
                .isEqualTo(kept);
        await().atMost(Duration.ofSeconds(5)).until(() -> runtime.removed.contains(container));
    }

    @Test
    void shouldReportItCannotServeAndTryAgainLaterWhenASandboxCannotStart() {
        runtime.failCreate = true;

        assertThatThrownBy(() -> sandboxes.acquire(attempt(), WORKSPACE, () -> false))
                .isInstanceOf(SandboxException.class);
        assertThat(sandboxes.available()).isFalse();

        runtime.failCreate = false;
        assertThat(Objects.requireNonNull(
                        sandboxes.acquire(attempt(), WORKSPACE, () -> false).containerId()))
                .startsWith("container-");
        assertThat(sandboxes.available()).isTrue();
    }

    @Test
    void shouldRemoveEverySandboxWhenTheWorkerStops() {
        filled();
        sandboxes.acquire(attempt(), WORKSPACE, () -> false);

        sandboxes.close();

        assertThat(runtime.running).isEmpty();
    }
}
