package de.tum.cit.aet.hephaestus.core.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.observability.LogCapture;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
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
                .run(new ActionStore.Claim(ID, null, ActionKind.FILE_ISSUE, "key", "{}", 2, 7L, true, null));

        assertThat(result).isEqualTo(new ActionResult.Reread(ActionExecutor.backoff(2)));
    }

    @Test
    void shouldStopWaitingForAProviderThatHoldsTheCallPastTheWait() throws InterruptedException {
        var release = new CountDownLatch(1);
        var executor = executor(attempt -> {
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new ActionResult.Done(null);
        });
        long started = System.nanoTime();

        executor.runBatch(List.of(claim(false)), Duration.ofMillis(200));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        release.countDown();
    }

    @Test
    void shouldEndTheOtherStaleActionsAndStillClaimWhenOneDomainRowFails() {
        UUID failing = UUID.randomUUID();
        UUID ending = UUID.randomUUID();
        var executor = executor(attempt -> new ActionResult.Done(null));
        when(store.findStale(anyInt())).thenReturn(List.of(failing, ending));
        when(store.endStale(eq(failing), any())).thenThrow(new IllegalStateException("domain row refused"));
        when(store.endStale(eq(ending), any())).thenReturn(Optional.empty());
        when(store.claim(anyInt(), any())).thenReturn(List.of());

        executor.runDue();

        verify(store).endStale(eq(ending), any());
        verify(store).claim(anyInt(), any());
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
                    .run(new ActionStore.Claim(ID, 42L, ActionKind.EMAIL, "key", "{}", 3, 7L, false, null));

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
        when(store.settle(any(), anyLong(), any(), any())).thenReturn(Optional.empty());
        return new ActionExecutor(store, JsonMapper.builder().build(), adapters(execute), new SimpleMeterRegistry());
    }

    private static List<ActionAdapter> adapters(Function<ActionAttempt, ActionResult> execute) {
        return Arrays.stream(ActionKind.values())
                .map(kind -> (ActionAdapter) new ActionAdapter() {
                    @Override
                    public ActionKind kind() {
                        return kind;
                    }

                    @Override
                    public ActionResult execute(ActionAttempt attempt) {
                        return execute.apply(attempt);
                    }
                })
                .toList();
    }

    private static ActionStore.Claim claim(boolean writeStarted) {
        return new ActionStore.Claim(ID, null, ActionKind.EMAIL, "key", "{}", 1, 7L, writeStarted, null);
    }
}
