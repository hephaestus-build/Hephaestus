package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCapacityException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsPods;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.Serial;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The conversation tools Pods of the installation, held in {@code conversation_tools_pod}. A pool of {@link #poolSize}
 * started Pods waits ahead. A turn attempt's first call that needs one binds one, on whichever worker the call reaches,
 * and that Pod serves the attempt for its whole life. Every bind is fenced on the attempt's live lease.
 *
 * <p>One rule removes Pods ({@link #reap}): a Pod whose attempt holds no live lease goes, and so do a pooled Pod past
 * {@link #POOL_LIFE} and a Pod that never started. A worker that stops removes nothing: another worker serves its
 * attempts' calls, with their Pods.
 */
@WorkspaceAgnostic("Tools Pods are installation-wide; each bound row names the workspace of its turn")
public class ConversationToolsPods implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConversationToolsPods.class);

    /**
     * One CPU, 1 GiB and 256 processes. A Pod lives as long as its attempt, so the runtime bound is the longest one
     * any Pod may have; each command has its own.
     */
    public static final ResourceLimits LIMITS = new ResourceLimits(1.0, ResourceLimits.MAX_RUNTIME);

    /**
     * How long a pooled Pod may wait for an attempt. Its Job ends at {@link ResourceLimits#MAX_RUNTIME}, so a bound Pod
     * still has more time left than the longest turn.
     */
    static final Duration POOL_LIFE = Duration.ofHours(1);

    /**
     * How long a Pod may take to start before its row goes: the Job's two start waits of two minutes each, and one reap
     * interval.
     */
    static final Duration START_GRACE = Duration.ofMinutes(5);

    /** How long a failed start keeps this worker from offering tools: one failure is no lasting fault. */
    private static final Duration START_FAILURE_HOLD = Duration.ofMinutes(1);

    private static final Duration POLL = Duration.ofMillis(500);

    /** The context of an attempt changed while it ran: its next bind says so once, then gets a new Pod. */
    public static final class ContextChanged extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        ContextChanged() {
            super("The context folder changed", null, false, false);
        }
    }

    private final ToolsPods pods;
    private final ConversationToolsPodRepository rows;
    private final TransactionTemplate transactions;
    private final int poolSize;
    private final MeterRegistry meters;
    private final AtomicBoolean refilling = new AtomicBoolean();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
    /** When a start of a Pod last failed, in {@link System#nanoTime}; {@code null} after a start that worked. */
    private volatile @Nullable Long lastStartFailed;

    private volatile boolean closed;

    public ConversationToolsPods(
            ToolsPods pods,
            ConversationToolsPodRepository rows,
            TransactionTemplate transactions,
            int poolSize,
            MeterRegistry meters) {
        this.pods = pods;
        this.rows = rows;
        this.transactions = transactions;
        this.poolSize = poolSize;
        this.meters = meters;
    }

    /** Whether this worker can bind tools Pods: its runtime is ready, and its last start of one did not fail. */
    public boolean available() {
        Long failed = lastStartFailed;
        return !closed && pods.ready() && (failed == null || System.nanoTime() - failed > START_FAILURE_HOLD.toNanos());
    }

    public ToolsPods runtime() {
        return pods;
    }

    /**
     * The attempt's tools Pod: the one it holds, or one from the pool, or a new one when the pool has none ready.
     *
     * @throws ContextChanged once after the attempt's Pod was revoked because its workspace's data changed
     * @throws SandboxException when no Pod can start, or the attempt holds no live lease
     */
    public ToolsPods.Started bind(TurnAttempt turn, BooleanSupplier expired) {
        Timer.Sample wait = null;
        boolean ready = false;
        try {
            while (true) {
                if (expired.getAsBoolean() || Thread.currentThread().isInterrupted() || closed) {
                    throw new SandboxCancelledException("The tools request ended while waiting for tools");
                }
                Optional<ConversationToolsPod> held = rows.findBound(turn.turnId(), turn.attempt());
                if (held.isPresent()) {
                    ConversationToolsPod pod = held.get();
                    if (pod.isRevoked()) {
                        release(turn, pod.getName(), true);
                        throw new ContextChanged();
                    }
                    if (pod.getAddress() != null) {
                        ready = true;
                        return started(pod);
                    }
                    // Another call of the attempt starts it, maybe on another worker.
                    pause();
                    continue;
                }
                Optional<String> pooled = transactions.execute(status -> rows.bindPooled(
                        turn.workspaceId(), turn.turnId(), turn.attempt(), turn.runId(), POOL_LIFE.toSeconds()));
                if (pooled != null && pooled.isPresent()) {
                    refill();
                    continue;
                }
                if (!startFor(turn)) {
                    if (wait == null) {
                        wait = Timer.start(meters);
                    }
                    pause();
                }
            }
        } finally {
            if (wait != null) {
                wait.stop(Timer.builder(AgentMetrics.MENTOR_TURN_TOOLS_WAIT)
                        .description("Wait after tools quota refusal until tools are ready, including Pod start")
                        .tag("outcome", ready ? "ready" : "abandoned")
                        .publishPercentileHistogram()
                        .register(meters));
            }
        }
    }

    /**
     * Starts a Pod bound to the attempt. Its row comes first, so no reap removes the Job as unknown.
     *
     * @return false when the tools quota has no room now
     */
    private boolean startFor(TurnAttempt turn) {
        String sandboxId = UUID.randomUUID().toString();
        String name = ToolsPods.name(sandboxId);
        UUID nonce = UUID.randomUUID();
        Integer inserted = transactions.execute(status ->
                rows.insertBound(name, nonce, turn.workspaceId(), turn.turnId(), turn.attempt(), turn.runId()));
        if (inserted == null || inserted == 0) {
            if (rows.findBound(turn.turnId(), turn.attempt()).isPresent()) {
                // A parallel call of the attempt was first.
                return true;
            }
            throw new SandboxException("The attempt holds no live lease");
        }
        try {
            ToolsPods.Started started = pods.create(sandboxId, nonce.toString(), LIMITS);
            lastStartFailed = null;
            transactions.executeWithoutResult(status -> rows.started(name, started.host()));
            refill();
            return true;
        } catch (SandboxCapacityException full) {
            release(turn, name, false);
            if (full.reason() == SandboxCapacityException.Reason.QUOTA_EXCEEDED) {
                return false;
            }
            throw full;
        } catch (RuntimeException failed) {
            lastStartFailed = System.nanoTime();
            release(turn, name, false);
            throw failed instanceof SandboxException sandbox
                    ? sandbox
                    : new SandboxException("A tools Pod could not start", failed);
        }
    }

    private static ToolsPods.Started started(ConversationToolsPod pod) {
        return new ToolsPods.Started(
                pod.getName(), pod.getAddress(), ToolsPods.PORT, pod.getNonce().toString());
    }

    private static void pause() {
        try {
            Thread.sleep(POLL);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new SandboxCancelledException("The turn stopped while waiting for tools", cancelled);
        }
    }

    /**
     * Removes the attempt's tools Pod after its loop found it broken: what it holds may be partial. The attempt goes on;
     * its next bind gets a new one. Another Pod of the attempt stays: a late report never removes it.
     */
    public void discard(TurnAttempt turn, String name) {
        release(turn, name, false);
    }

    private void release(TurnAttempt turn, String name, boolean revoked) {
        Integer released =
                transactions.execute(status -> rows.releaseBound(name, turn.turnId(), turn.attempt(), revoked));
        if (released != null && released > 0) {
            removeLater(name);
        }
    }

    /** Removes the attempt's Pod: the attempt ended. A command that runs in it dies with it. Returns at once. */
    public void ended(UUID turnId, int attempt) {
        List<String> ended = transactions.execute(status -> rows.releaseAttempt(turnId, attempt));
        if (ended != null) ended.forEach(this::removeLater);
    }

    /**
     * Deletes the Jobs of the revoked Pods now, not at the next reap. The change that made their data stale revoked
     * them ({@link ConversationToolsPodRevocations}); each row stays until its attempt's next bind says so.
     */
    public void removeRevoked() {
        rows.findRevokedNames().forEach(this::removeLater);
    }

    /**
     * The one cleanup rule: removes the Pods that no attempt with a live lease holds, pooled Pods past their life, Pods
     * that never started, and Jobs that no row names. Then fills the pool. Every worker runs it; each step is
     * idempotent.
     */
    public void reap() {
        if (closed) {
            return;
        }
        List<String> unused =
                transactions.execute(status -> rows.deleteUnused(POOL_LIFE.toSeconds(), START_GRACE.toSeconds()));
        if (unused != null) {
            unused.forEach(name -> {
                log.info("Removing tools Pod {}: no attempt with a live lease holds it", name);
                pods.remove(name);
            });
        }
        // A revocation's delete may have failed; until a bind consumes the row, its Pod must not serve.
        rows.findRevokedNames().forEach(pods::remove);
        Set<String> named = new HashSet<>(rows.findAllNames());
        List<ToolsPods.Listed> listed;
        try {
            listed = pods.list();
        } catch (SandboxException e) {
            // The class only: an API failure can quote the API's response body.
            log.warn("Could not list the tools Pods: {}", e.getClass().getSimpleName());
            return;
        }
        for (ToolsPods.Listed job : listed) {
            // A Job younger than the grace may belong to a row written after the read above.
            if (!named.contains(job.name()) && job.age().compareTo(START_GRACE) > 0) {
                log.info("Removing tools Pod {}: no row names it", job.name());
                pods.remove(job.name());
            }
        }
        refill();
    }

    /** Fills the installation's pool in the background, one Pod at a time, while no other worker fills it. */
    void refill() {
        if (closed || !pods.ready() || !refilling.compareAndSet(false, true)) {
            return;
        }
        try {
            var filling = background.submit(this::fill);
            log.trace("Filling the tools Pod pool: {}", filling);
        } catch (RejectedExecutionException stopped) {
            refilling.set(false);
        }
    }

    private void fill() {
        try {
            while (!closed) {
                String sandboxId = UUID.randomUUID().toString();
                String name = ToolsPods.name(sandboxId);
                UUID nonce = UUID.randomUUID();
                Boolean admitted = transactions.execute(status -> {
                    if (!rows.lockPool() || rows.countPooled(POOL_LIFE.toSeconds()) >= poolSize) return false;
                    rows.insertPooled(name, nonce);
                    return true;
                });
                if (!Boolean.TRUE.equals(admitted)) {
                    return;
                }
                try {
                    ToolsPods.Started started = pods.create(sandboxId, nonce.toString(), LIMITS);
                    lastStartFailed = null;
                    transactions.executeWithoutResult(status -> rows.started(name, started.host()));
                } catch (RuntimeException failed) {
                    if (!(failed instanceof SandboxCapacityException)) lastStartFailed = System.nanoTime();
                    transactions.executeWithoutResult(status -> rows.deleteById(name));
                    pods.remove(name);
                    throw failed;
                }
            }
        } catch (SandboxCapacityException full) {
            if (full.reason() == SandboxCapacityException.Reason.QUOTA_EXCEEDED) {
                log.debug("The tools Pod pool waits for quota");
            } else {
                log.warn("The tools quota cannot hold one tools Pod");
            }
        } catch (RuntimeException e) {
            log.warn("Could not fill the tools Pod pool: {}", e.getClass().getSimpleName());
        } finally {
            refilling.set(false);
        }
    }

    private void removeLater(String name) {
        try {
            var removal = background.submit(() -> pods.remove(name));
            log.trace("Removing tools Pod {}: {}", name, removal);
        } catch (RejectedExecutionException stopped) {
            pods.remove(name);
        }
    }

    /** Stops binding and filling. It removes no Pod: the attempts that hold them go on with another worker. */
    @Override
    public void close() {
        closed = true;
        background.shutdown();
    }
}
