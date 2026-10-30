package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCapacityException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.Serial;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The turn sandboxes of this worker. A pool of {@link #poolSize} empty sandboxes waits ahead. The first {@code read},
 * {@code bash} or {@code load_context} call of a turn attempt takes one, and the pool fills again in the background.
 * An assigned sandbox belongs to its attempt until the attempt ends, and never goes back to the pool: the end removes
 * it with every file in it. The assignment lives in this worker's memory only; the sandbox carries its own id and the
 * worker's id as labels.
 *
 * <p>{@link #reconcile} runs at startup and every minute. It removes this worker's sandboxes that it does not know,
 * the assigned sandboxes of attempts that no longer run here, and the sandboxes of workers that are positively gone.
 * It never removes a sandbox of a live other worker.
 */
@WorkspaceAgnostic("Sandboxes are Kubernetes resources; each assignment names the workspace of its turn")
public class TurnSandboxes implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TurnSandboxes.class);

    /**
     * One CPU, 1 GiB and 256 processes. A sandbox lives as long as its attempt, so the runtime bound is the longest
     * one any sandbox may have; each command has its own.
     */
    public static final ResourceLimits LIMITS = new ResourceLimits(1.0, ResourceLimits.MAX_RUNTIME);

    /** The attempt a sandbox serves. */
    public record Attempt(UUID turnId, int number) {}

    enum State {
        POOLED,
        ASSIGNED,
        REMOVED
    }

    /** One sandbox. Its folder state belongs to the attempt it is assigned to. */
    public static final class Sandbox {
        final String sandboxId;
        volatile @Nullable String containerId;
        volatile State state = State.POOLED;
        volatile @Nullable Attempt attempt;
        volatile long workspaceId;

        /**
         * Staging and loads change the folder and take the write lock; {@code read} and {@code bash} take the read
         * lock, so a command never sees a folder half replaced.
         */
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        /** Whether the context documents are staged; set under the write lock. */
        volatile boolean staged;

        final AtomicInteger commands = new AtomicInteger();
        final AtomicInteger loads = new AtomicInteger();

        Sandbox(String sandboxId) {
            this.sandboxId = sandboxId;
        }

        public String containerId() {
            String id = containerId;
            if (id == null || state == State.REMOVED) {
                throw new SandboxException("The turn sandbox was removed");
            }
            return id;
        }

        boolean removed() {
            return state == State.REMOVED;
        }
    }

    /** The context of an attempt changed while it ran: its next call says so once, then gets a new sandbox. */
    public static final class ContextChanged extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        ContextChanged() {
            super("The context folder changed", null, false, false);
        }
    }

    private final ExecSandboxes sandboxes;
    private final int poolSize;
    private final MeterRegistry meters;
    private final Map<Attempt, Integer> quotaWaiting = new ConcurrentHashMap<>();

    /**
     * The lifecycle fence: creation's end, assignment, release, discard and close change {@link #pool},
     * {@link #assigned}, {@link #ended}, {@link #changed} and {@link #closed} only while holding it. Kubernetes calls run
     * outside it.
     */
    private final Object lifecycle = new Object();

    private final ArrayDeque<Sandbox> pool = new ArrayDeque<>();
    private final Map<String, Sandbox> known = new ConcurrentHashMap<>();
    private final Map<Attempt, Sandbox> assigned = new HashMap<>();
    private final Set<Attempt> changed = new HashSet<>();

    /** Attempts that ended here: a call that comes late gets no sandbox. An attempt ends within its deadline. */
    private final Cache<Attempt, Boolean> ended =
            Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).build();

    private final AtomicBoolean refilling = new AtomicBoolean();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean lastStartFailed;
    private boolean closed;

    public TurnSandboxes(ExecSandboxes sandboxes, int poolSize, MeterRegistry meters) {
        this.sandboxes = sandboxes;
        this.poolSize = poolSize;
        this.meters = meters;
    }

    /**
     * Whether this worker can serve turn sandboxes: it can name itself on them, so that its next start removes what a
     * crash left, its runtime is ready, and the last start of one did not fail.
     */
    public boolean available() {
        synchronized (lifecycle) {
            if (closed) return false;
        }
        return sandboxes.workerInstance() != null && sandboxes.ready() && !lastStartFailed;
    }

    public ExecSandboxes runtime() {
        return sandboxes;
    }

    /**
     * The attempt's sandbox: the one it holds, or one from the pool, or a new one when the pool is empty.
     *
     * @throws ContextChanged once after the attempt's sandbox was released because its workspace's data changed
     * @throws SandboxException when no sandbox can start, or the attempt ended
     */
    public Sandbox acquire(Attempt attempt, long workspaceId, BooleanSupplier expired) {
        Timer.Sample wait = null;
        boolean ready = false;
        try {
            while (true) {
                if (expired.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new SandboxCancelledException("The tools request ended while waiting for tools");
                synchronized (lifecycle) {
                    Sandbox held = admitted(attempt);
                    if (held != null) {
                        ready = true;
                        return held;
                    }
                    Sandbox pooled = pool.pollFirst();
                    if (pooled != null) {
                        assign(attempt, workspaceId, pooled);
                        refill();
                        ready = true;
                        return pooled;
                    }
                }
                Sandbox started;
                try {
                    started = start();
                } catch (SandboxCapacityException full) {
                    if (full.reason() != SandboxCapacityException.Reason.QUOTA_EXCEEDED) throw full;
                    started = null;
                }
                if (started == null) {
                    if (wait == null) {
                        wait = Timer.start(meters);
                        quotaWaiting.merge(attempt, 1, Integer::sum);
                    }
                    synchronized (lifecycle) {
                        if (admitted(attempt) != null || !pool.isEmpty()) continue;
                        try {
                            // A local release wakes this wait; the timeout also sees room freed by another worker.
                            lifecycle.wait(1000);
                        } catch (InterruptedException cancelled) {
                            Thread.currentThread().interrupt();
                            throw new SandboxException("The turn stopped while waiting for tools", cancelled);
                        }
                    }
                    continue;
                }
                synchronized (lifecycle) {
                    Sandbox held;
                    try {
                        held = admitted(attempt);
                        if (expired.getAsBoolean())
                            throw new SandboxCancelledException("The tools request ended while tools started");
                    } catch (RuntimeException ended) {
                        // Release or context change won while the Pod started.
                        removeLater(started);
                        throw ended;
                    }
                    if (held != null) {
                        // A parallel call of this attempt was first; keep this empty Pod in the warm pool.
                        pool.addLast(started);
                        ready = true;
                        return held;
                    }
                    assign(attempt, workspaceId, started);
                }
                refill();
                ready = true;
                return started;
            }
        } finally {
            if (wait != null) {
                quotaWaiting.computeIfPresent(attempt, (key, count) -> count == 1 ? null : count - 1);
                wait.stop(Timer.builder(AgentMetrics.MENTOR_TURN_TOOLS_WAIT)
                        .description("Wait after tools quota refusal until tools are ready, including Pod start")
                        .tag("outcome", ready ? "ready" : "abandoned")
                        .publishPercentileHistogram()
                        .register(meters));
            }
        }
    }

    /** Whether this attempt waits for tools after the resource quota refused a Pod. */
    public boolean waitingForQuota(Attempt attempt) {
        return quotaWaiting.containsKey(attempt);
    }

    /** The attempt's live sandbox, or null when it may take one. Holds {@link #lifecycle}. */
    private @Nullable Sandbox admitted(Attempt attempt) {
        if (closed || ended.getIfPresent(attempt) != null) {
            throw new SandboxException("The attempt ended");
        }
        if (changed.remove(attempt)) {
            throw new ContextChanged();
        }
        Sandbox held = assigned.get(attempt);
        return held != null && !held.removed() ? held : null;
    }

    private void assign(Attempt attempt, long workspaceId, Sandbox sandbox) {
        sandbox.state = State.ASSIGNED;
        sandbox.attempt = attempt;
        sandbox.workspaceId = workspaceId;
        assigned.put(attempt, sandbox);
        lifecycle.notifyAll();
    }

    /** Whether the attempt holds a sandbox that is not removed. A load stops waiting when this turns false. */
    public boolean holds(Attempt attempt, Sandbox sandbox) {
        synchronized (lifecycle) {
            return assigned.get(attempt) == sandbox && !sandbox.removed();
        }
    }

    /**
     * Removes the attempt's sandbox, if it holds one: the attempt ended (an answer, a stop, the deadline, a lost lease,
     * a lost host, or a retry claimed here). A command that runs in it dies with it. A sandbox that starts for the
     * attempt after this is removed at once. Returns at once.
     */
    public void release(UUID turnId, int number) {
        Attempt attempt = new Attempt(turnId, number);
        Sandbox sandbox;
        synchronized (lifecycle) {
            ended.put(attempt, Boolean.TRUE);
            changed.remove(attempt);
            sandbox = assigned.remove(attempt);
            lifecycle.notifyAll();
        }
        if (sandbox != null) {
            removeLater(sandbox);
        }
    }

    /**
     * Removes the sandbox after a staging failure: what it holds may be partial. The attempt goes on; its next call
     * gets a new sandbox and stages again.
     */
    public void discard(Attempt attempt, Sandbox sandbox) {
        synchronized (lifecycle) {
            assigned.remove(attempt, sandbox);
        }
        removeLater(sandbox);
    }

    /**
     * Removes every sandbox of the workspace's running attempts: its data changed (an erasure, a consent change, a
     * hidden repository), so what they staged may be stale. Each attempt's next call says so, then starts over.
     */
    public void releaseWorkspace(long workspaceId) {
        releaseWhere(sandbox -> sandbox.workspaceId == workspaceId);
    }

    /** As {@link #releaseWorkspace}, for every workspace: changes may have been missed. */
    public void releaseAll() {
        releaseWhere(sandbox -> true);
    }

    private void releaseWhere(Predicate<Sandbox> affected) {
        List<Sandbox> removed = new ArrayList<>();
        synchronized (lifecycle) {
            for (var entry : List.copyOf(assigned.entrySet())) {
                if (affected.test(entry.getValue())) {
                    assigned.remove(entry.getKey());
                    changed.add(entry.getKey());
                    removed.add(entry.getValue());
                }
            }
        }
        removed.forEach(this::removeLater);
    }

    /**
     * Removes what this worker does not hold: its own sandboxes that it does not know (a start before this one left
     * them), assigned sandboxes of attempts that no longer run here, and sandboxes of workers that are gone. Then fills
     * the pool.
     *
     * @param running whether a turn attempt runs on this worker
     */
    public void reconcile(BiPredicate<UUID, Integer> running) {
        List<Attempt> gone;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            gone = assigned.keySet().stream()
                    .filter(attempt -> !running.test(attempt.turnId(), attempt.number()))
                    .toList();
            // Released attempts never come back, so their marks only matter while the attempt runs.
            changed.removeIf(attempt -> !running.test(attempt.turnId(), attempt.number()));
        }
        for (Attempt attempt : gone) {
            log.info(
                    "Removing the turn sandbox of attempt {} of turn {}: it no longer runs here",
                    attempt.number(),
                    attempt.turnId());
            release(attempt.turnId(), attempt.number());
        }
        String self = sandboxes.workerInstance();
        if (self == null) {
            return;
        }
        List<ExecSandboxes.Listed> listed;
        try {
            listed = sandboxes.list();
        } catch (SandboxException e) {
            // The class only, as below: an exec failure can quote the API's response body.
            log.warn("Could not list the turn sandboxes: {}", e.getClass().getSimpleName());
            return;
        }
        for (ExecSandboxes.Listed sandbox : listed) {
            boolean own = self.equals(sandbox.workerInstance());
            if ((own && !known.containsKey(sandbox.sandboxId())) || (!own && sandbox.workerGone())) {
                log.info("Removing a turn sandbox that no worker holds: {}", sandbox.sandboxId());
                sandboxes.remove(sandbox.id());
            }
        }
        refill();
    }

    /** Fills the pool in the background, one sandbox at a time. */
    void refill() {
        synchronized (lifecycle) {
            if (closed || pool.size() >= poolSize || sandboxes.workerInstance() == null || !sandboxes.ready()) {
                return;
            }
        }
        if (!refilling.compareAndSet(false, true)) {
            return;
        }
        var filling = background.submit(() -> {
            try {
                while (pooled() < poolSize) {
                    Sandbox sandbox = start();
                    synchronized (lifecycle) {
                        if (closed) {
                            break;
                        }
                        pool.addLast(sandbox);
                    }
                }
            } catch (SandboxCapacityException full) {
                if (full.reason() == SandboxCapacityException.Reason.QUOTA_EXCEEDED) {
                    log.debug("Turn tools pool waits for quota");
                } else {
                    log.warn("The tools quota cannot hold one tools Pod");
                }
            } catch (SandboxException e) {
                log.warn(
                        "Could not fill the turn sandbox pool: {}", e.getClass().getSimpleName());
            } finally {
                refilling.set(false);
            }
        });
        log.trace("Filling the turn sandbox pool: {}", filling);
    }

    /**
     * Starts an empty sandbox. It is known before it exists, so a concurrent reconcile does not remove it, and a close
     * that comes while it starts removes it once it exists.
     */
    private Sandbox start() {
        Sandbox sandbox = new Sandbox(UUID.randomUUID().toString());
        synchronized (lifecycle) {
            if (closed) throw new SandboxException("This worker stops");
            known.put(sandbox.sandboxId, sandbox);
        }
        try {
            sandbox.containerId = sandboxes.create(sandbox.sandboxId, LIMITS);
            lastStartFailed = false;
        } catch (RuntimeException e) {
            lastStartFailed = !(e instanceof SandboxCapacityException);
            known.remove(sandbox.sandboxId);
            throw e instanceof SandboxException failed
                    ? failed
                    : new SandboxException("A turn sandbox could not start", e);
        }
        boolean stopping;
        synchronized (lifecycle) {
            stopping = closed;
        }
        if (stopping) {
            removeNow(sandbox);
            throw new SandboxException("This worker stops");
        }
        return sandbox;
    }

    private void removeLater(Sandbox sandbox) {
        sandbox.state = State.REMOVED;
        try {
            var removal = background.submit(() -> removeNow(sandbox));
            log.trace("Removing turn sandbox {}: {}", sandbox.sandboxId, removal);
        } catch (RejectedExecutionException stopped) {
            removeNow(sandbox);
        }
    }

    private void removeNow(Sandbox sandbox) {
        sandbox.state = State.REMOVED;
        String id = sandbox.containerId;
        if (id != null) {
            sandboxes.remove(id);
        }
        known.remove(sandbox.sandboxId);
        synchronized (lifecycle) {
            lifecycle.notifyAll();
        }
    }

    /** The sandboxes this worker holds now, pooled and assigned. */
    int held() {
        return known.size();
    }

    int pooled() {
        synchronized (lifecycle) {
            return pool.size();
        }
    }

    /**
     * Removes every sandbox this worker created, when it stops: those it holds now, and those that start after this,
     * as each start ends ({@link #start}).
     */
    @Override
    public void close() {
        synchronized (lifecycle) {
            closed = true;
            pool.clear();
            assigned.clear();
            lifecycle.notifyAll();
        }
        List.copyOf(known.values()).forEach(this::removeNow);
        background.shutdown();
    }
}
