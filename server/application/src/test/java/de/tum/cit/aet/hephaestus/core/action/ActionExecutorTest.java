package de.tum.cit.aet.hephaestus.core.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.observability.LogCapture;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
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
        verify(store).settle(ID, 7L, result);
    }

    @Test
    void shouldRetryWhenTheAdapterThrowsBeforeAnyWrite() {
        try (var logs = new LogCapture(ActionExecutor.class)) {
            ActionResult result = executor(attempt -> {
                        throw new IllegalStateException("contact lookup failed for someone@example.com");
                    })
                    .run(claim(false));

            assertThat(result).isEqualTo(new ActionResult.Retry(ActionExecutor.backoff(1)));
            verify(store).settle(ID, 7L, result);
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
        when(store.settle(any(), anyLong(), any()))
                .thenReturn(Optional.of(new ActionStore.Settled(
                        ID, 42L, ActionKind.EMAIL, ActionState.UNCERTAIN, ActionStopReason.WRITE_UNCONFIRMED)));

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
    void shouldEndUncertainWhenAnEarlierAttemptMayHaveWritten() {
        ActionResult result = executor(attempt -> {
                    throw new IllegalStateException("readback failed");
                })
                .run(claim(true));

        assertThat(result).isEqualTo(new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED));
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

    private ActionExecutor executor(Function<ActionAttempt, ActionResult> execute) {
        when(store.settle(any(), anyLong(), any())).thenReturn(Optional.empty());
        ActionAdapter adapter = new ActionAdapter() {
            @Override
            public ActionKind kind() {
                return ActionKind.EMAIL;
            }

            @Override
            public ActionResult execute(ActionAttempt attempt) {
                return execute.apply(attempt);
            }
        };
        return new ActionExecutor(store, JsonMapper.builder().build(), List.of(adapter), new SimpleMeterRegistry());
    }

    private static ActionStore.Claim claim(boolean writeStarted) {
        return new ActionStore.Claim(ID, null, ActionKind.EMAIL, "key", "{}", 1, 7L, writeStarted, null);
    }
}
