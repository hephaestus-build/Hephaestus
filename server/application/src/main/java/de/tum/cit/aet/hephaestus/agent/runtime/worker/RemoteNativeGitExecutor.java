package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerControlWebSocketHandler;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerDisconnectedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerSession;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerSessionRegistry;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitAck;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitCancel;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitOperation;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitOutput;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.ObjectMapper;

/**
 * The application server's Git executor when the worker runs in its own container: each operation is
 * dispatched over the control channel to the worker that holds the repository's mirror and streamed
 * back frame by frame. A repository stays on the worker it was placed on while that session lives;
 * once the session is gone it is placed again by hashing the repository over the live workers, so a
 * hub restart or worker reconnect lands every repository on the same worker again as long as the
 * set of workers is unchanged. A query on a worker without the mirror answers "not cloned" and the
 * caller fetches; a lost control session fails its operation and ingestion retries from persisted
 * commit checkpoints.
 */
public final class RemoteNativeGitExecutor implements NativeGitExecutor {
    static final int CHUNK_BYTES = 128 * 1024;
    private final WorkerSessionRegistry registry;
    private final ObjectMapper mapper;
    private final ConcurrentMap<RepositoryKey, WorkerSession> affinity = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Pending> pending = new ConcurrentHashMap<>();

    public RemoteNativeGitExecutor(
            WorkerSessionRegistry registry, WorkerControlWebSocketHandler hub, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
        hub.setGitOutputHandler(this::receive);
    }

    @Override
    public void execute(RepositoryKey key, Request request, Duration timeout, OutputStream output) {
        if (request.operation().readsCanonicalEvidence())
            throw new IllegalArgumentException("Historical reads require canonical job evidence");
        WorkerSession session = affinity.compute(
                key, (ignored, previous) -> previous != null && previous.isOpen() ? previous : place(key));
        remote(
                session,
                key.workspaceId(),
                key.repositoryId(),
                mapper.writeValueAsString(request),
                false,
                timeout,
                output);
    }

    private WorkerSession place(RepositoryKey key) {
        List<WorkerSession> live = registry.sessions().stream()
                .filter(WorkerSession::isOpen)
                .sorted(Comparator.comparing(WorkerSession::workerId))
                .toList();
        if (live.isEmpty()) throw new IllegalStateException("No connected Git worker");
        return live.get(Math.floorMod(Long.hashCode(key.repositoryId()), live.size()));
    }

    @Override
    public void executeInSnapshot(Path canonical, Request request, Duration timeout, OutputStream output) {
        throw new IllegalStateException("Canonical evidence belongs to its worker");
    }

    /** Every open worker is asked, whichever of them fails; the first failure is the one reported. */
    @Override
    public void deleteRepository(long repositoryId) {
        if (repositoryId <= 0) throw new IllegalArgumentException("Invalid repository");
        RuntimeException failure = null;
        try {
            for (var session : registry.sessions()) {
                if (!session.isOpen()) continue;
                try {
                    remote(session, 0, repositoryId, "", true, Duration.ofMinutes(2), OutputStream.nullOutputStream());
                } catch (RuntimeException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
        } finally {
            affinity.keySet().removeIf(key -> key.repositoryId() == repositoryId);
        }
        if (failure != null) throw failure;
    }

    private void remote(
            WorkerSession session,
            long workspaceId,
            long repositoryId,
            String json,
            boolean delete,
            Duration timeout,
            OutputStream output) {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid deadline");
        UUID id = UUID.randomUUID();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        Pending state = new Pending(session);
        pending.put(id, state);
        boolean completed = false;
        try {
            if (!session.send(
                    new GitOperation(id, session.sessionId(), workspaceId, repositoryId, json, deadline, delete)))
                throw new IllegalStateException("Git dispatch failed");
            long sequence = 0;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0 || !session.isOpen())
                    throw new IllegalStateException("Git operation session or deadline expired");
                GitOutput frame = state.frames.poll(Math.min(remaining, 1000), TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                if (frame.terminal()) {
                    if (!frame.success()
                            || frame.sequence() != sequence
                            || !frame.data().isEmpty()) throw new IllegalStateException("Git worker operation failed");
                    completed = true;
                    return;
                }
                if (frame.sequence() != sequence++) throw new IllegalStateException("Git output sequence mismatch");
                if (frame.data().length() > 4 * ((CHUNK_BYTES + 2) / 3))
                    throw new IllegalStateException("Git output frame too large");
                byte[] bytes = Base64.getDecoder().decode(frame.data());
                if (bytes.length > CHUNK_BYTES) throw new IllegalStateException("Git output frame too large");
                output.write(bytes);
                if (!session.send(new GitAck(id, frame.sequence())))
                    throw new IllegalStateException("Git output acknowledgement failed");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Git output failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git operation interrupted", e);
        } finally {
            pending.remove(id);
            if (!completed) session.send(new GitCancel(id));
        }
    }

    private void receive(WorkerSession session, GitOutput output) {
        var state = pending.get(output.operationId());
        if (state == null || !state.session.sessionId().equals(session.sessionId())) return;
        if (!state.frames.offer(output)) {
            state.frames.clear();
            state.frames.offer(new GitOutput(output.operationId(), -1, "", true, false));
        }
    }

    /** Control-plane event: the worker that owned these operations is gone, so each fails now. */
    @EventListener
    public void disconnected(WorkerDisconnectedEvent event) {
        affinity.entrySet().removeIf(entry -> entry.getValue().sessionId().equals(event.sessionId()));
        pending.forEach((id, state) -> {
            if (state.session.sessionId().equals(event.sessionId())) {
                state.frames.clear();
                state.frames.offer(new GitOutput(id, -1, "", true, false));
            }
        });
    }

    private record Pending(WorkerSession session, ArrayBlockingQueue<GitOutput> frames) {
        Pending(WorkerSession session) {
            this(session, new ArrayBlockingQueue<>(1));
        }
    }
}
