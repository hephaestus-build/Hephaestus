package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitAck;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitCancel;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitOperation;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitOutput;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.WorkerControlFrame;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.Operation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.Request;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * The worker's side of {@link RemoteNativeGitExecutor}: runs each Git operation the hub dispatches on
 * this worker's own executor and streams the output back, one acknowledged frame at a time. The
 * executor's capacity bound applies here exactly as it does to the worker's local reviews.
 */
public final class WorkerGitOperationHandler {
    private static final int CHUNK_BYTES = RemoteNativeGitExecutor.CHUNK_BYTES;
    private final WorkerControlClient client;
    private final NativeGitExecutor executor;
    private final ObjectMapper mapper;
    private final ConcurrentMap<UUID, Running> running = new ConcurrentHashMap<>();

    public WorkerGitOperationHandler(WorkerControlClient client, NativeGitExecutor executor, ObjectMapper mapper) {
        this.client = client;
        this.executor = executor;
        this.mapper = mapper;
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
        Thread thread = Thread.ofVirtual().unstarted(() -> run(operation, state));
        state.thread = thread;
        thread.start();
        if (state.cancelled) thread.interrupt();
    }

    private void run(GitOperation operation, Running state) {
        var stream = new OutputStream() {
            long sequence;

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
                            || !client.sendRequired(
                                    new GitOutput(operation.operationId(), sequence, data, false, true)))
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
        };
        boolean success = false;
        try {
            long millis = operation.deadlineEpochMillis() - System.currentTimeMillis();
            if (millis <= 0 || millis > Duration.ofHours(2).toMillis())
                throw new IllegalArgumentException("Invalid Git deadline");
            if (operation.delete()) executor.deleteRepository(operation.repositoryId());
            else {
                if (operation.requestJson().length() > 64 * 1024)
                    throw new IllegalArgumentException("Git request too large");
                Request request = mapper.readValue(operation.requestJson(), Request.class);
                if (request.operation() == Operation.CITED_BLOBS
                        || request.operation() == Operation.HISTORICAL_BLOB
                        || request.operation() == Operation.SCAN_SECRETS)
                    throw new IllegalArgumentException("Canonical evidence cannot be remotely selected");
                executor.execute(
                        new RepositoryKey(operation.workspaceId(), operation.repositoryId()),
                        request,
                        Duration.ofMillis(millis),
                        stream);
            }
            success = true;
        } catch (RuntimeException ignored) {
            // The result deliberately carries no provider exception or credential-bearing request.
        } finally {
            client.sendRequired(new GitOutput(operation.operationId(), stream.sequence, "", true, success));
            running.remove(operation.operationId(), state);
        }
    }

    @PreDestroy
    public void cancelRunning() {
        running.values().forEach(Running::cancel);
    }

    private static final class Running {
        final ArrayBlockingQueue<Long> acks = new ArrayBlockingQueue<>(1);
        volatile @Nullable Thread thread;
        volatile boolean cancelled;

        void cancel() {
            cancelled = true;
            acks.clear();
            acks.offer(-1L);
            var current = thread;
            if (current != null) current.interrupt();
        }
    }
}
