package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitAck;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitOperation;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitOutput;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.Operation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.Request;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class WorkerGitOperationHandlerTest {
    private final WorkerControlClient client = mock(WorkerControlClient.class);
    private final NativeGitExecutor executor = mock(NativeGitExecutor.class);
    private final List<GitOutput> frames = new CopyOnWriteArrayList<>();
    private final CountDownLatch terminal = new CountDownLatch(1);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final WorkerGitOperationHandler handler =
            new WorkerGitOperationHandler(client, executor, new ObjectMapper(), meterRegistry);

    WorkerGitOperationHandlerTest() {
        when(client.isConnected()).thenReturn(true);
        when(client.controlSessionId()).thenReturn("session");
        when(client.sendRequired(any(GitOutput.class))).thenAnswer(invocation -> {
            GitOutput frame = invocation.getArgument(0);
            frames.add(frame);
            if (frame.terminal()) terminal.countDown();
            else handler.handle(new GitAck(frame.operationId(), frame.sequence()));
            return true;
        });
    }

    private GitOperation operation(Request request) {
        return new GitOperation(
                UUID.randomUUID(),
                "session",
                7,
                11,
                new ObjectMapper().writeValueAsString(request),
                System.currentTimeMillis() + Duration.ofSeconds(5).toMillis(),
                false);
    }

    @Test
    void shouldRunTheOperationLocallyAndStreamAcknowledgedFramesBackToTheHub() throws Exception {
        var request = new Request(Operation.COMMIT_IDS, List.of(), null, null);
        doAnswer(invocation -> {
                    OutputStream output = invocation.getArgument(3);
                    output.write("abc\n".getBytes(StandardCharsets.UTF_8));
                    return null;
                })
                .when(executor)
                .execute(eq(new RepositoryKey(7, 11)), eq(request), any(), any());
        handler.handle(operation(request));
        assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(frames).hasSize(2);
        assertThat(new String(Base64.getDecoder().decode(frames.get(0).data()), StandardCharsets.UTF_8))
                .isEqualTo("abc\n");
        assertThat(frames.get(1).terminal()).isTrue();
        assertThat(frames.get(1).success()).isTrue();
    }

    @Test
    void shouldReportFailureWithoutTheCauseWhenTheExecutorRejectsTheOperation() throws Exception {
        var request = new Request(Operation.COMMIT_IDS, List.of(), null, null);
        doAnswer(invocation -> {
                    throw new IllegalStateException("token=private-token");
                })
                .when(executor)
                .execute(any(), any(), any(), any());
        handler.handle(operation(request));
        assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(frames).singleElement().satisfies(frame -> {
            assertThat(frame.terminal()).isTrue();
            assertThat(frame.success()).isFalse();
            assertThat(frame.data()).isEmpty();
        });
        assertThat(meterRegistry
                        .get(AgentMetrics.WORKER_GIT_OPERATIONS_FAILED)
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldCoalesceSmallWritesIntoOneFrame() throws Exception {
        var request = new Request(Operation.COMMIT_IDS, List.of(), null, null);
        doAnswer(invocation -> {
                    OutputStream output = invocation.getArgument(3);
                    for (int line = 0; line < 100; line++) output.write("line\n".getBytes(StandardCharsets.UTF_8));
                    return null;
                })
                .when(executor)
                .execute(any(), any(), any(), any());
        handler.handle(operation(request));
        assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(frames).hasSize(2);
        assertThat(new String(Base64.getDecoder().decode(frames.get(0).data()), StandardCharsets.UTF_8))
                .isEqualTo("line\n".repeat(100));
    }

    @Test
    void shouldRefuseCanonicalEvidenceSelectionFromTheHub() throws Exception {
        var historical =
                new Request(Operation.HISTORICAL_BLOB, List.of("a".repeat(40), "b".repeat(40), "f"), null, null);
        handler.handle(operation(historical));
        assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(frames)
                .singleElement()
                .satisfies(frame -> assertThat(frame.success()).isFalse());
        verify(executor, org.mockito.Mockito.never()).execute(any(), any(), any(), any());
    }

    @Test
    void shouldIgnoreOperationsAddressedToAnotherControlSession() {
        var request = new Request(Operation.COMMIT_IDS, List.of(), null, null);
        var foreign = new GitOperation(
                UUID.randomUUID(),
                "other",
                7,
                11,
                new ObjectMapper().writeValueAsString(request),
                Long.MAX_VALUE,
                false);
        handler.handle(foreign);
        assertThat(frames).isEmpty();
        verify(executor, org.mockito.Mockito.never()).execute(any(), any(), any(), any());
    }
}
