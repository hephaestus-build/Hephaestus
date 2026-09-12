package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerControlWebSocketHandler;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerDisconnectedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerSession;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerSessionRegistry;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitAck;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitCancel;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitOperation;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitOutput;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.WorkerControlFrame;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.Operation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.Request;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class RemoteNativeGitExecutorTest {
    private static final RepositoryKey KEY = new RepositoryKey(7, 11);
    private static final Request FETCH =
            new Request(Operation.FETCH, List.of(), "https://example.com/team/repo.git", "private-token");
    private static final Request QUERY = new Request(Operation.COMMIT_IDS, List.of(), null, null);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final WorkerSession session = mock(WorkerSession.class);
    private final WorkerSessionRegistry registry = mock(WorkerSessionRegistry.class);
    private final AtomicReference<BiConsumer<WorkerSession, GitOutput>> receiver = new AtomicReference<>();
    private final RemoteNativeGitExecutor executor;

    RemoteNativeGitExecutorTest() {
        var hub = mock(WorkerControlWebSocketHandler.class);
        doAnswer(invocation -> {
                    receiver.set(invocation.getArgument(0));
                    return null;
                })
                .when(hub)
                .setGitOutputHandler(any());
        when(registry.sessions()).thenReturn(List.of(session));
        when(session.isOpen()).thenReturn(true);
        when(session.sessionId()).thenReturn("session");
        when(session.workerId()).thenReturn("worker-b");
        executor = new RemoteNativeGitExecutor(registry, hub, new ObjectMapper());
    }

    private void deliver(GitOutput frame) {
        receiver.get().accept(session, frame);
    }

    @Test
    void shouldStreamRemoteOutputWithAcknowledgementAndNeverLeakCredentials() {
        byte[] expected = new byte[] {0, 1, (byte) 255, 10};
        AtomicReference<GitOperation> request = new AtomicReference<>();
        when(session.send(any())).thenAnswer(invocation -> {
            WorkerControlFrame frame = invocation.getArgument(0);
            if (frame instanceof GitOperation operation) {
                request.set(operation);
                deliver(new GitOutput(
                        operation.operationId(), 0, Base64.getEncoder().encodeToString(expected), false, true));
            } else if (frame instanceof GitAck ack) {
                deliver(new GitOutput(ack.operationId(), 1, "", true, true));
            }
            return true;
        });
        var output = new ByteArrayOutputStream();
        executor.execute(KEY, FETCH, TIMEOUT, output);
        assertThat(output.toByteArray()).isEqualTo(expected);
        assertThat(request.get().workspaceId()).isEqualTo(7);
        assertThat(request.get().repositoryId()).isEqualTo(11);
        assertThat(request.get().toString()).doesNotContain("private-token", "repo.git");
    }

    @Test
    void shouldRejectOutOfOrderOutputRatherThanReturningPartialSuccess() {
        when(session.send(any())).thenAnswer(invocation -> {
            if (invocation.getArgument(0) instanceof GitOperation operation)
                deliver(new GitOutput(operation.operationId(), 4, "", false, true));
            return true;
        });
        assertThatThrownBy(() -> executor.execute(KEY, FETCH, TIMEOUT, new ByteArrayOutputStream()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sequence");
    }

    @Test
    void shouldFailLostDispatch() {
        when(session.send(any())).thenReturn(false);
        assertThatThrownBy(() -> executor.execute(KEY, FETCH, TIMEOUT, new ByteArrayOutputStream()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dispatch");
    }

    private WorkerSession otherWorker() {
        var other = mock(WorkerSession.class);
        when(other.isOpen()).thenReturn(true);
        when(other.sessionId()).thenReturn("other-session");
        when(other.workerId()).thenReturn("worker-a");
        return other;
    }

    private record Dispatch(WorkerSession worker, GitOperation operation) {}

    /** Every dispatched operation succeeds at once; the returned list records where each one went. */
    private List<Dispatch> answeringWorkers(WorkerSession... workers) {
        List<Dispatch> dispatched = new CopyOnWriteArrayList<>();
        for (var worker : workers)
            when(worker.send(any())).thenAnswer(invocation -> {
                if (invocation.getArgument(0) instanceof GitOperation operation) {
                    dispatched.add(new Dispatch(worker, operation));
                    receiver.get().accept(worker, new GitOutput(operation.operationId(), 0, "", true, true));
                }
                return true;
            });
        return dispatched;
    }

    @Test
    void shouldKeepARepositoryOnTheWorkerItWasPlacedOn() {
        var other = otherWorker();
        when(registry.sessions()).thenReturn(List.of(session, other));
        var dispatched = answeringWorkers(session, other);

        executor.execute(KEY, QUERY, TIMEOUT, new ByteArrayOutputStream());
        executor.execute(KEY, QUERY, TIMEOUT, new ByteArrayOutputStream());

        assertThat(dispatched)
                .extracting(Dispatch::worker)
                .hasSize(2)
                .containsOnly(dispatched.getFirst().worker());
    }

    @Test
    void shouldPlaceTheRepositoryAgainWhenItsWorkerSessionCloses() {
        var other = otherWorker();
        when(registry.sessions()).thenReturn(List.of(session, other));
        var dispatched = answeringWorkers(session, other);
        executor.execute(KEY, QUERY, TIMEOUT, new ByteArrayOutputStream());
        var placed = dispatched.getFirst().worker();
        when(placed.isOpen()).thenReturn(false);

        executor.execute(KEY, QUERY, TIMEOUT, new ByteArrayOutputStream());

        assertThat(dispatched).hasSize(2);
        assertThat(dispatched.get(1).worker()).isNotSameAs(placed);
    }

    @Test
    void shouldFailWhenNoWorkerIsConnected() {
        when(registry.sessions()).thenReturn(List.of());
        assertThatThrownBy(() -> executor.execute(KEY, QUERY, TIMEOUT, new ByteArrayOutputStream()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No connected Git worker");
    }

    @Test
    void shouldDeleteTheRepositoryOnEveryOpenWorkerAndStillServeItAfterwards() {
        var other = otherWorker();
        var closed = mock(WorkerSession.class);
        when(closed.isOpen()).thenReturn(false);
        when(registry.sessions()).thenReturn(List.of(session, other, closed));
        var dispatched = answeringWorkers(session, other);
        executor.execute(KEY, QUERY, TIMEOUT, new ByteArrayOutputStream());

        executor.deleteRepository(KEY.repositoryId());

        assertThat(dispatched)
                .filteredOn(dispatch -> dispatch.operation().delete())
                .allSatisfy(dispatch ->
                        assertThat(dispatch.operation().repositoryId()).isEqualTo(KEY.repositoryId()))
                .extracting(Dispatch::worker)
                .containsExactlyInAnyOrder(session, other);
        verify(closed, never()).send(any());
        executor.execute(KEY, QUERY, TIMEOUT, new ByteArrayOutputStream());
        assertThat(dispatched.getLast().operation().delete()).isFalse();
        assertThat(dispatched.getLast().worker().isOpen()).isTrue();
    }

    @Test
    void shouldFailTheOperationWhenTheDeadlinePassesWithoutAnAnswer() {
        when(session.send(any())).thenReturn(true);
        assertThatThrownBy(() -> executor.execute(KEY, QUERY, Duration.ofMillis(50), new ByteArrayOutputStream()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Git operation session or deadline expired");
    }

    @Test
    void shouldNotCancelAnOperationThatCompleted() {
        when(session.send(any())).thenAnswer(invocation -> {
            if (invocation.getArgument(0) instanceof GitOperation operation)
                deliver(new GitOutput(operation.operationId(), 0, "", true, true));
            return true;
        });
        executor.execute(KEY, FETCH, TIMEOUT, new ByteArrayOutputStream());
        verify(session, never()).send(any(GitCancel.class));
    }

    @Test
    void shouldCancelAnOperationThatFailed() {
        when(session.send(any())).thenAnswer(invocation -> {
            if (invocation.getArgument(0) instanceof GitOperation operation)
                deliver(new GitOutput(operation.operationId(), 0, "", true, false));
            return true;
        });
        assertThatThrownBy(() -> executor.execute(KEY, FETCH, TIMEOUT, new ByteArrayOutputStream()))
                .isInstanceOf(IllegalStateException.class);
        verify(session).send(any(GitCancel.class));
    }

    @Test
    void shouldFailTheOperationWhenItsWorkerDisconnects() {
        when(session.send(any())).thenAnswer(invocation -> {
            if (invocation.getArgument(0) instanceof GitOperation)
                executor.disconnected(
                        new WorkerDisconnectedEvent("worker", "session", "closed", java.time.Instant.EPOCH));
            return true;
        });
        assertThatThrownBy(() -> executor.execute(KEY, FETCH, TIMEOUT, new ByteArrayOutputStream()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed");
    }

    @Test
    void shouldKeepCanonicalEvidenceReadsOnTheWorker() {
        var historical =
                new Request(Operation.HISTORICAL_BLOB, List.of("a".repeat(40), "b".repeat(40), "f"), null, null);
        assertThatThrownBy(() -> executor.execute(KEY, historical, TIMEOUT, new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(session);
    }
}
