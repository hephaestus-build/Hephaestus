package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitAck;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitCancel;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitOperation;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitOutput;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.WorkerControlFrame;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.Request;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * The worker's side of {@link RemoteNativeGitExecutor}: runs each Git operation the hub dispatches on
 * this worker's own executor and streams the output back, one acknowledged frame at a time. The
 * executor's capacity bound applies here exactly as it does to the worker's local reviews.
 */
public final class WorkerGitOperationHandler {
    private static final Logger log = LoggerFactory.getLogger(WorkerGitOperationHandler.class);
    private static final int CHUNK_BYTES = RemoteNativeGitExecutor.CHUNK_BYTES;
    private final WorkerControlClient client;
    private final NativeGitExecutor executor;
    private final Executor threads;
    private final ObjectMapper mapper;
    private final Counter failed;
    private final ConcurrentMap<UUID, Running> running = new ConcurrentHashMap<>();

    /**
     * @param threads platform threads, as many as the executor admits at once: an operation attaches to
     *     its container through docker-java, which pins a virtual thread's carrier for the whole run
     */
    public WorkerGitOperationHandler(
            WorkerControlClient client,
            NativeGitExecutor executor,
            Executor threads,
            ObjectMapper mapper,
            MeterRegistry meterRegistry) {
        this.client = client;
        this.executor = executor;
        this.threads = threads;
        this.mapper = mapper;
        this.failed = Counter.builder(AgentMetrics.WORKER_GIT_OPERATIONS_FAILED)
                .description("Hub-dispatched Git operations this worker reported as failed")
                .register(meterRegistry);
        client.setGitHandler(this::handle, this::cancelRunning);
    }

    void handle(WorkerControlFrame frame) {
        switch (frame) {
            case GitAck ack -> {
                var state = running.get(ack.operationId());
                if (state != null && !state.acks.offer(ack.sequence())) state.cancel();
            }
            case GitCancel cancel -> {
                var state = running.get(cancel.operationId());
                if (state != null) state.cancel();
            }
            case GitOperation operation -> start(operation);
            default -> {}
        }
    }

    private void start(GitOperation operation) {
        if (!client.isConnected() || !operation.sessionId().equals(client.controlSessionId())) return;
        var state = new Running();
        if (running.putIfAbsent(operation.operationId(), state) != null) return;
        try {
            threads.execute(() -> run(operation, state));
        } catch (RejectedExecutionException shuttingDown) {
            running.remove(operation.operationId(), state);
            client.sendRequired(new GitOutput(operation.operationId(), 0, "", true, false));
        }
    }

    private void run(GitOperation operation, Running state) {
        var frames = new FrameStream(operation, state);
        // One hub round trip per frame, so small writes are coalesced up to the frame size.
        var output = new BufferedOutputStream(frames, CHUNK_BYTES);
        boolean success = false;
        try {
            state.claim(Thread.currentThread());
            long millis = operation.deadlineEpochMillis() - System.currentTimeMillis();
            if (millis <= 0 || millis > Duration.ofHours(2).toMillis())
                throw new IllegalArgumentException("Invalid Git deadline");
            if (operation.delete()) executor.deleteRepository(operation.repositoryId());
            else {
                Request request = mapper.readValue(operation.requestJson(), Request.class);
                if (request.operation().readsCanonicalEvidence())
                    throw new IllegalArgumentException("Canonical evidence cannot be remotely selected");
                executor.execute(
                        new RepositoryKey(operation.workspaceId(), operation.repositoryId()),
                        request,
                        Duration.ofMillis(millis),
                        output);
            }
            output.flush();
            success = true;
        } catch (IOException | RuntimeException | InterruptedException e) {
            // The terminal frame carries no cause: the request may hold a token and the cause a provider URL.
            failed.increment();
            log.warn(
                    "Git operation {} failed on this worker: {}",
                    operation.operationId(),
                    e.getClass().getSimpleName());
        } finally {
            client.sendRequired(new GitOutput(operation.operationId(), frames.sequence, "", true, success));
            running.remove(operation.operationId(), state);
            state.release();
        }
    }

    /** Sends each write as one acknowledged {@link GitOutput} frame, at most {@link #CHUNK_BYTES} each. */
    private final class FrameStream extends OutputStream {
        private final GitOperation operation;
        private final Running state;
        long sequence;

        FrameStream(GitOperation operation, Running state) {
            this.operation = operation;
            this.state = state;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value});
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            for (int index = 0; index < length; index += CHUNK_BYTES) {
                int count = Math.min(CHUNK_BYTES, length - index);
                String data = Base64.getEncoder()
                        .encodeToString(Arrays.copyOfRange(bytes, offset + index, offset + index + count));
                if (state.cancelled
                        || !client.sendRequired(new GitOutput(operation.operationId(), sequence, data, false, true)))
                    throw new IOException("Git control session lost");
                try {
                    long remaining = operation.deadlineEpochMillis() - System.currentTimeMillis();
                    Long ack = remaining > 0 ? state.acks.poll(remaining, TimeUnit.MILLISECONDS) : null;
                    if (ack == null || ack != sequence) throw new IOException("Git output acknowledgement failed");
                    sequence++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Git stream interrupted", e);
                }
            }
        }
    }

    @PreDestroy
    public void cancelRunning() {
        running.values().forEach(Running::cancel);
    }

    private static final class Running {
        final ArrayBlockingQueue<Long> acks = new ArrayBlockingQueue<>(1);
        private @Nullable Thread thread;
        volatile boolean cancelled;

        /** The pool thread is shared with later operations, so a cancel may only reach it while claimed. */
        synchronized void claim(Thread current) throws InterruptedException {
            if (cancelled) throw new InterruptedException("Cancelled before it started");
            thread = current;
        }

        synchronized void release() {
            thread = null;
            Thread.interrupted();
        }

        void cancel() {
            cancelled = true;
            acks.clear();
            acks.offer(-1L);
            synchronized (this) {
                if (thread != null) thread.interrupt();
            }
        }
    }
}
