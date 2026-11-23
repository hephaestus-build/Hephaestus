package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunNotifications;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The wait of a sandbox's {@code next} for its subject's next run (ADR 0079 § Claim, priority and reap). A request
 * that finds no run waits until a run of its subject is due, and then claims it through the same claim as before: the
 * wait only decides when a request tries again, so a wake on two replicas cannot lease one run twice.
 *
 * <p>A request waits on its own gateway thread, which is virtual ({@link SandboxGatewayConnector}): a waiting request
 * holds no carrier. One pass on this replica's own thread finds the waiting subjects that have a due run, with one
 * query, and wakes only their requests. A pass runs when a run is queued on any replica ({@link
 * AgentRunNotifications}), and every {@code recheck} for runs that became due without a notification, as at the end of
 * a retry's delay.
 *
 * <p>At most {@code capacity} requests wait at once; a further one claims once and answers at once. A request is no
 * use of its sandbox, so a sandbox that waits still goes idle.
 */
@Component
@ConditionalOnAgentRuntime
@Profile("!specs & !cds-training")
public class AgentNextWaits implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AgentNextWaits.class);

    /** Below the driver's 30-second limit for one gateway call, with room for the claim after a late wake. */
    static final Duration WAIT = Duration.ofSeconds(25);

    /** As often as a sandbox pulled before requests waited. */
    static final Duration RECHECK = Duration.ofSeconds(2);

    /** The notifications of a burst of queued runs within this time share one pass. */
    private static final long GATHER_MILLIS = 50;

    private final AgentRuns runs;
    private final AgentRunNotifications notifications;
    private final Duration wait;
    private final Duration recheck;
    private final Semaphore capacity;
    private final Map<UUID, Waiter> waiters = new ConcurrentHashMap<>();
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private volatile boolean running;
    private @Nullable ScheduledExecutorService passes;

    /** At most half the gateway's connections wait, so model calls always find a connection. */
    @Autowired
    public AgentNextWaits(AgentRuns runs, AgentRunNotifications notifications, SandboxGatewayProperties gateway) {
        this(runs, notifications, Math.max(1, gateway.maxConnections() / 2), WAIT, RECHECK);
    }

    AgentNextWaits(AgentRuns runs, AgentRunNotifications notifications, int capacity, Duration wait, Duration recheck) {
        this.runs = runs;
        this.notifications = notifications;
        this.capacity = new Semaphore(capacity);
        this.wait = wait;
        this.recheck = recheck;
    }

    /**
     * Claims through {@code claim} until it leases a run, the wait ends, or this replica stops. A later request of the
     * same sandbox ends this one's wait, also when the later one cannot wait: the sandbox pulls one run at a time, so
     * its caller gave the earlier one up.
     */
    public <T> Optional<T> claim(
            AgentSandboxes.LiveSandbox sandbox, Set<AgentRunKind> kinds, Supplier<Optional<T>> claim) {
        Waiter abandoned = waiters.remove(sandbox.id());
        if (abandoned != null) abandoned.supersede();
        if (!running || kinds.isEmpty() || !capacity.tryAcquire()) return claim.get();
        var waiter = new Waiter(sandbox, kinds);
        long until = System.nanoTime() + wait.toNanos();
        try {
            Waiter earlier = waiters.put(sandbox.id(), waiter);
            if (earlier != null) earlier.supersede();
            // The waiter is registered before the first claim, so a run queued after that claim still wakes it. A
            // stop that did not see the waiter has already cleared the flag that each wait reads first.
            while (true) {
                Optional<T> claimed = claim.get();
                if (claimed.isPresent() || !running || !waiter.await(until - System.nanoTime()) || !running)
                    return claimed;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            waiters.remove(sandbox.id(), waiter);
            capacity.release();
        }
    }

    /** Asks for a pass soon; asks while one waits make no second pass. */
    void wake() {
        ScheduledExecutorService executor = passes;
        if (executor == null || !pending.compareAndSet(false, true)) return;
        try {
            var _ = executor.schedule(
                    () -> {
                        // Before the pass reads, so a run queued while it reads asks for the next pass.
                        pending.set(false);
                        pass();
                    },
                    GATHER_MILLIS,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            pending.set(false);
        }
    }

    /** Wakes each waiting request whose subject has a run of its kinds that a claim would offer now. */
    void pass() {
        if (waiters.isEmpty()) return;
        try {
            Map<String, List<Waiter>> bySubject = new HashMap<>();
            Set<AgentRunKind> kinds = EnumSet.noneOf(AgentRunKind.class);
            Set<Long> workspaceIds = new HashSet<>();
            Set<String> subjectKeys = new HashSet<>();
            for (Waiter waiter : waiters.values()) {
                bySubject
                        .computeIfAbsent(
                                subject(waiter.sandbox.workspaceId(), waiter.sandbox.subjectKey()),
                                key -> new ArrayList<>())
                        .add(waiter);
                kinds.addAll(waiter.kinds);
                workspaceIds.add(waiter.sandbox.workspaceId());
                subjectKeys.add(waiter.sandbox.subjectKey());
            }
            for (AgentRuns.DueRun due : runs.dueOf(kinds, workspaceIds, subjectKeys)) {
                for (Waiter waiter :
                        bySubject.getOrDefault(subject(due.run().workspaceId(), due.concurrencyKey()), List.of())) {
                    if (waiter.kinds.contains(due.kind())) waiter.wake();
                }
            }
        } catch (RuntimeException e) {
            log.atWarn()
                    .addKeyValue("event.name", "agent.next.wake.failed")
                    .addKeyValue("error.type", e.getClass().getName())
                    .log("Waking the waiting next requests failed; they claim again at the next pass");
        }
    }

    private static String subject(long workspaceId, String subjectKey) {
        return workspaceId + ":" + subjectKey;
    }

    /** The requests waiting now. */
    int waiting() {
        return waiters.size();
    }

    @Override
    public synchronized void start() {
        if (running) return;
        var executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("agent-next-waits").daemon(true).factory());
        var _ = executor.scheduleWithFixedDelay(
                this::pass, recheck.toMillis(), recheck.toMillis(), TimeUnit.MILLISECONDS);
        pending.set(false);
        passes = executor;
        running = true;
        if (subscribed.compareAndSet(false, true)) notifications.subscribe(kind -> wake());
    }

    /** Before the review drain stops claims: each waiting request answers that no run is due, and none waits again. */
    @Override
    public synchronized void stop() {
        running = false;
        waiters.values().forEach(Waiter::wake);
        ScheduledExecutorService executor = passes;
        passes = null;
        if (executor != null) executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return AgentShutdownConfiguration.DRAIN_PHASE + 1;
    }

    /** One waiting request. */
    private static final class Waiter {

        private final AgentSandboxes.LiveSandbox sandbox;
        private final Set<AgentRunKind> kinds;
        private final Semaphore woken = new Semaphore(0);
        private volatile boolean superseded;

        Waiter(AgentSandboxes.LiveSandbox sandbox, Set<AgentRunKind> kinds) {
            this.sandbox = sandbox;
            this.kinds = kinds;
        }

        void wake() {
            woken.release();
        }

        void supersede() {
            superseded = true;
            woken.release();
        }

        /** Whether the request should claim again: it was woken within {@code nanos}, and no later request took over. */
        boolean await(long nanos) throws InterruptedException {
            if (superseded || nanos <= 0 || !woken.tryAcquire(nanos, TimeUnit.NANOSECONDS)) return false;
            woken.drainPermits();
            return !superseded;
        }
    }
}
