package de.tum.cit.aet.hephaestus.core.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.observability.LogCapture;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

/** The executor's last guard: an adapter that throws never turns a write that may exist into a retry. */
class ActionExecutorTest extends BaseUnitTest {
    private static final UUID ID = UUID.randomUUID();
    private final ActionStore store = mock(ActionStore.class);

    @Test
    void shouldEndUncertainWhenTheAdapterThrowsAfterTheFence() {
        when(store.beginWrite(eq(ID), eq(7L), any())).thenReturn(true);

        ActionResult result = executor(attempt -> {
                    attempt.fence().beginWrite();
                    throw new IllegalStateException("socket closed");
                })
                .run(claim(false));

        assertThat(result).isEqualTo(new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED));
        verify(store).settle(eq(ID), eq(7L), eq(result), any());
    }

    @Test
    void shouldRetryWhenTheAdapterThrowsBeforeAnyWrite() {
        try (var logs = new LogCapture(ActionExecutor.class)) {
            ActionResult result = executor(attempt -> {
                        throw new IllegalStateException("contact lookup failed for someone@example.com");
                    })
                    .run(claim(false));

            assertThat(result).isEqualTo(new ActionResult.Retry(ActionExecutor.backoff(1)));
            verify(store).settle(eq(ID), eq(7L), eq(result), any());
            assertThat(logs.events("action.attempt.failed"))
                    .as("the event names the action and the failure class, never the provider's message")
                    .singleElement()
                    .satisfies(fields -> assertThat(fields)
                            .containsEntry("action.id", ID.toString())
                            .containsEntry("action.kind", "EMAIL")
                            .containsEntry("action.attempt", "1")
                            .containsEntry("error.type", IllegalStateException.class.getName())
                            .doesNotContainKey("workspace.id")
                            .doesNotContainValue("contact lookup failed for someone@example.com"));
        }
    }

    @Test
    void shouldNameTheActionAndItsWorkspaceWhenItEndsUncertain() {
        var executor = executor(attempt -> new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED));
        when(store.settle(any(), anyLong(), any(), any()))
                .thenReturn(Optional.of(new ActionStore.Settled(
                        ID,
                        42L,
                        ActionKind.EMAIL,
                        "key",
                        ActionState.UNCERTAIN,
                        ActionStopReason.WRITE_UNCONFIRMED,
                        null)));

        try (var logs = new LogCapture(ActionExecutor.class)) {
            executor.run(claim(true));

            assertThat(logs.events("action.ended.uncertain"))
                    .singleElement()
                    .satisfies(fields -> assertThat(fields)
                            .containsEntry("action.id", ID.toString())
                            .containsEntry("workspace.id", "42")
                            .containsEntry("action.reason", "WRITE_UNCONFIRMED"));
        }
    }

    @Test
    void shouldShowEveryUncertainEndAtZeroFromTheStartSoItsFirstCountIsAnIncrease() {
        var meters = new SimpleMeterRegistry();
        new ActionExecutor(
                store, JsonMapper.builder().build(), adapters(attempt -> new ActionResult.Done(null)), meters);

        for (ActionStopReason reason : ActionStopReason.values())
            assertThat(meters.get(CoreMetrics.ACTION_ENDED)
                            .tag("kind", "EMAIL")
                            .tag("state", "UNCERTAIN")
                            .tag("reason", reason.name())
                            .counter()
                            .count())
                    .isZero();
    }

    @Test
    void shouldEndUncertainWhenAnEarlierAttemptMayHaveWritten() {
        ActionResult result = executor(attempt -> {
                    throw new IllegalStateException("readback failed");
                })
                .run(claim(true));

        assertThat(result).isEqualTo(new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED));
    }

    @Test
    void shouldReadBackAgainWhenAKindThatCanReadBackThrowsAfterTheFence() {
        ActionResult result = executor(attempt -> {
                    throw new IllegalStateException("socket closed");
                })
                .run(new ActionStore.Claim(ID, null, ActionKind.FILE_ISSUE, "key", "{}", 2, 7L, true, null, null));

        assertThat(result).isEqualTo(new ActionResult.Reread(ActionExecutor.backoff(2)));
    }

    /** On one replica, a draft that waits for its model holds one slot, and the next run claims another kind's action. */
    @Test
    void shouldRunAnotherKindsActionWhileADraftAttemptStillWaits() throws Exception {
        var draftWaits = new CountDownLatch(1);
        var executor = executor(
                attempt -> {
                    if (attempt.kind() == ActionKind.DRAFT_REPORT) awaitQuietly(draftWaits);
                    return new ActionResult.Done(null);
                },
                ActionExecutor.LEASE);
        when(store.claim(anyInt(), any(), any()))
                .thenReturn(List.of(claim(ActionKind.DRAFT_REPORT)))
                .thenReturn(List.of(claim(ActionKind.FILE_ISSUE)));

        var draft = executor.startDue();
        var filing = executor.startDue();

        assertThat(filing)
                .singleElement()
                .satisfies(
                        attempt -> assertThat(attempt.get(5, TimeUnit.SECONDS)).isEqualTo(new ActionResult.Done(null)));
        assertThat(draft)
                .singleElement()
                .satisfies(attempt -> assertThat(attempt).isNotDone());
        verify(store).claim(eq(ActionExecutor.SLOTS - 1), any(), any());
        draftWaits.countDown();
        assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void shouldNeverRunMoreAttemptsAtOnceThanItHasSlots() {
        var release = new CountDownLatch(1);
        var running = new AtomicInteger();
        var most = new AtomicInteger();
        var executor = executor(
                attempt -> {
                    most.accumulateAndGet(running.incrementAndGet(), Math::max);
                    awaitQuietly(release);
                    running.decrementAndGet();
                    return new ActionResult.Done(null);
                },
                ActionExecutor.LEASE);
        when(store.claim(anyInt(), any(), any())).thenAnswer(call -> claims(call.getArgument(0)));

        assertThat(executor.startDue()).hasSize(ActionExecutor.SLOTS);
        assertThat(executor.startDue()).isEmpty();
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> running.get() == ActionExecutor.SLOTS);
        assertThat(executor.startDue()).isEmpty();

        release.countDown();
        assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(most.get()).isEqualTo(ActionExecutor.SLOTS);
        verify(store, times(1)).claim(anyInt(), any(), any());
    }

    /**
     * A provider that holds a call past the lease keeps its thread, but not its slot: the reaper owns its action. The
     * slot comes back once, so the capacity stays {@link ActionExecutor#SLOTS} after the late attempts end.
     */
    @Test
    void shouldFreeTheSlotOfAnAttemptWhoseLeaseRanOutExactlyOnce() {
        var release = new CountDownLatch(1);
        var hanging = new AtomicInteger();
        var executor = executor(
                attempt -> {
                    hanging.incrementAndGet();
                    awaitQuietly(release);
                    hanging.decrementAndGet();
                    return new ActionResult.Done(null);
                },
                Duration.ofMillis(200));
        var limits = new CopyOnWriteArrayList<Integer>();
        when(store.claim(anyInt(), any(), any())).thenAnswer(call -> {
            limits.add(call.getArgument(0));
            return claims(call.getArgument(0));
        });
        assertThat(executor.startDue()).hasSize(ActionExecutor.SLOTS);

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> !executor.startDue().isEmpty());

        assertThat(hanging.get()).as("the late calls still run").isGreaterThanOrEqualTo(ActionExecutor.SLOTS);
        release.countDown();
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> hanging.get() == 0);
        assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
        executor.startDue();
        assertThat(limits.getLast()).isEqualTo(ActionExecutor.SLOTS);
    }

    /** Spring pauses an inactive test context through the lifecycle and resumes it: the executor claims again. */
    @Test
    void shouldClaimAgainWhenStartedAfterAStop() {
        var executor = executor(attempt -> new ActionResult.Done(null));
        when(store.claim(anyInt(), any(), any())).thenReturn(List.of());

        executor.stop();
        assertThat(executor.startDue()).isEmpty();
        verify(store, never()).claim(anyInt(), any(), any());
        executor.start();
        executor.startDue();

        verify(store).claim(eq(ActionExecutor.SLOTS), any(), any());
    }

    @Test
    void shouldEndTheOtherStaleActionsAndStillClaimWhenOneDomainRowFails() {
        UUID failing = UUID.randomUUID();
        UUID ending = UUID.randomUUID();
        var executor = executor(attempt -> new ActionResult.Done(null));
        when(store.findStale(anyInt(), any())).thenReturn(List.of(failing, ending));
        when(store.endStale(eq(failing), any(), any())).thenThrow(new IllegalStateException("domain row refused"));
        when(store.endStale(eq(ending), any(), any())).thenReturn(Optional.empty());
        when(store.claim(anyInt(), any(), any())).thenReturn(List.of());

        executor.runDue();

        verify(store).endStale(eq(ending), any(), any());
        verify(store).claim(anyInt(), any(), any());
    }

    /** An adapter's own records name the action; the caller's log context is back after the attempt. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldNameTheActionInTheLogContextOfItsAttemptAndRestoreTheCallers(boolean failure) {
        var during = new AtomicReference<Map<String, String>>();
        MDC.put("workspace.id", "9");
        MDC.put("traceId", "0123456789abcdef0123456789abcdef");
        try {
            executor(attempt -> {
                        during.set(MDC.getCopyOfContextMap());
                        if (failure) throw new IllegalStateException("adapter failed");
                        return new ActionResult.Done(null);
                    })
                    .run(new ActionStore.Claim(ID, 42L, ActionKind.EMAIL, "key", "{}", 3, 7L, false, null, null));

            assertThat(during.get())
                    .containsEntry("action.id", ID.toString())
                    .containsEntry("action.kind", "EMAIL")
                    .containsEntry("action.attempt", "3")
                    .containsEntry("workspace.id", "42")
                    .containsEntry("traceId", "0123456789abcdef0123456789abcdef");
            assertThat(MDC.getCopyOfContextMap())
                    .isEqualTo(Map.of("workspace.id", "9", "traceId", "0123456789abcdef0123456789abcdef"));
        } finally {
            MDC.clear();
        }
    }

    /** An instance-level action has no workspace: it does not inherit the caller's. */
    @Test
    void shouldNotNameTheCallersWorkspaceForAnInstanceLevelAction() {
        var during = new AtomicReference<Map<String, String>>();
        MDC.put("workspace.id", "9");
        try {
            executor(attempt -> {
                        during.set(MDC.getCopyOfContextMap());
                        return new ActionResult.Done(null);
                    })
                    .run(claim(false));

            assertThat(during.get()).doesNotContainKey("workspace.id").containsEntry("action.id", ID.toString());
            assertThat(MDC.get("workspace.id")).isEqualTo("9");
        } finally {
            MDC.clear();
        }
    }

    private ActionExecutor executor(Function<ActionAttempt, ActionResult> execute) {
        return executor(execute, ActionExecutor.LEASE);
    }

    private ActionExecutor executor(Function<ActionAttempt, ActionResult> execute, Duration lease) {
        when(store.settle(any(), anyLong(), any(), any())).thenReturn(Optional.empty());
        return new ActionExecutor(
                store, JsonMapper.builder().build(), adapters(execute), new SimpleMeterRegistry(), lease);
    }

    private static List<ActionStore.Claim> claims(int count) {
        return IntStream.range(0, count).mapToObj(n -> claim(ActionKind.EMAIL)).toList();
    }

    private static ActionStore.Claim claim(ActionKind kind) {
        return new ActionStore.Claim(UUID.randomUUID(), null, kind, "key", "{}", 1, 7L, false, null, null);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<ActionAdapter> adapters(Function<ActionAttempt, ActionResult> execute) {
        return ActionTestSupport.adapters(execute);
    }

    private static ActionStore.Claim claim(boolean writeStarted) {
        return new ActionStore.Claim(ID, null, ActionKind.EMAIL, "key", "{}", 1, 7L, writeStarted, null, null);
    }
}
