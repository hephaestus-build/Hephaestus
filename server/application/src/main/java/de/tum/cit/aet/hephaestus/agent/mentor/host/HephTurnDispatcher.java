package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnSandboxes;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarter;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyCallsInFlight;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerDrainCoordinator;
import de.tum.cit.aet.hephaestus.mentor.MentorLeaseLostException;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs this worker's share of Heph turns on its Heph host: claims turns while fewer than its capacity run here, sends
 * each attempt to the host, renews its lease on the {@code tick} after a third of it passed, and ends a turn itself
 * when its host does not within {@code STOP_GRACE} of a developer or deadline stop. Every command names the attempt,
 * so a later attempt of the same turn is a different command target. A claimed turn that cannot start fails, and
 * claiming goes on.
 * {@code docs/contributor/unified-pi-runtime.mdx} § Turn lifecycle and § Dispatch have the protocol.
 */
public class HephTurnDispatcher implements MentorLedgerNotifications.Listener, SmartLifecycle, HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(HephTurnDispatcher.class);

    static final String COULD_NOT_START = "Heph could not start. Try again in a moment.";
    static final String TOOK_TOO_LONG = "Heph took too long to reply and stopped. Try again.";
    static final String COULD_NOT_FINISH = "Heph could not finish this reply. Try again.";
    static final String MODEL_STOPPED_ANSWERING =
            "Heph's AI model stopped responding, so Heph could not finish this reply. Try again in a moment.";

    /**
     * The phase of the worker drain: turns finish before the gateway the host writes through closes, together with
     * the review drain and under its one drain timeout.
     */
    static final int PHASE = WorkerDrainCoordinator.PHASE;

    /** How long a host has to end a turn it was told to stop. */
    static final Duration STOP_GRACE = Duration.ofSeconds(30);

    static final Duration TICK = Duration.ofSeconds(5);

    private static final Duration BACKSTOP = Duration.ofSeconds(30);
    private static final Duration HOST_READY_TIMEOUT = Duration.ofMinutes(3);

    private final MentorTurnStarter starter;
    private final MentorTurnService turns;
    private final MentorTurnCompletion completion;
    private final MentorLedgerNotifications notifications;
    private final HephHost host;
    private final ProxyCallsInFlight callsInFlight;
    private final TurnSandboxes sandboxes;
    private final AttemptTokenIssuer issuer;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String workerId;
    private final int capacity;
    private final Duration lease;
    private final Duration stopGrace;
    /** The worker's drain timeout: the review drain and this one share a shutdown phase and its timeout. */
    private final Duration drainTimeout;

    private final Counter claimFailures;

    /** The attempt of each turn that runs here. Removals notify its monitor, which the drain waits on. */
    private final Map<UUID, Running> running = new ConcurrentHashMap<>();

    private final Semaphore wake = new Semaphore(0);
    private volatile boolean active;
    private volatile @Nullable Thread claimer;

    public HephTurnDispatcher(
            MentorTurnStarter starter,
            MentorTurnService turns,
            MentorTurnCompletion completion,
            MentorLedgerNotifications notifications,
            HephHost host,
            ProxyCallsInFlight callsInFlight,
            TurnSandboxes sandboxes,
            AttemptTokenIssuer issuer,
            ObjectMapper mapper,
            Clock clock,
            MeterRegistry meters,
            String workerId,
            int capacity,
            Duration lease,
            Duration stopGrace,
            Duration drainTimeout) {
        this.starter = starter;
        this.turns = turns;
        this.completion = completion;
        this.notifications = notifications;
        this.host = host;
        this.callsInFlight = callsInFlight;
        this.sandboxes = sandboxes;
        this.issuer = issuer;
        this.mapper = mapper;
        this.clock = clock;
        this.workerId = workerId;
        this.capacity = capacity;
        this.lease = lease;
        this.stopGrace = stopGrace;
        this.drainTimeout = drainTimeout;
        host.onLost(this::hostLost);
        Gauge.builder(AgentMetrics.MENTOR_HOST_TURNS_ACTIVE, running, Map::size)
                .description("Heph turns this worker's host runs")
                .register(meters);
        Gauge.builder(AgentMetrics.MENTOR_HOST_READY, host, h -> h.isReady() ? 1.0 : 0.0)
                .description("1 while this worker's Heph host has its command channel open, 0 otherwise")
                .register(meters);
        this.claimFailures = Counter.builder(AgentMetrics.MENTOR_TURN_CLAIM_FAILURE)
                .description("Heph turns this worker could not claim or start because of an error")
                .register(meters);
    }

    private static final class Running {
        final UUID turnId;
        final long workspaceId;
        final int attempt;
        /** The attempt as its tools act: the claimed turn's conversation, and the conversation's account. */
        final TurnAttempt identity;

        final Instant deadline;
        final Instant claimed;
        volatile Instant renewed;
        /** The partial answer this attempt last stored; {@code null} before its first. */
        volatile @Nullable JsonNode partial;

        volatile boolean started;
        volatile @Nullable String stopReason;
        volatile @Nullable Instant stopSent;
        volatile boolean overDeadline;

        Running(MentorTurn turn, long accountId, Instant now, Instant deadline) {
            this.turnId = turn.getId();
            this.workspaceId = turn.getWorkspaceId();
            this.attempt = turn.getAttempt();
            this.identity = new TurnAttempt(turnId, workspaceId, accountId, turn.getConversationId(), attempt);
            this.deadline = deadline;
            this.claimed = now;
            this.renewed = now;
        }
    }

    @Override
    public void start() {
        active = true;
        notifications.subscribe(this);
        claimer = Thread.ofPlatform().name("heph-turn-claimer").daemon(true).start(this::claimLoop);
        wake.release();
    }

    /** A rolling replacement must have its own command channel before it can replace the old worker. */
    @Override
    public Health health() {
        return (capacity == 0 || host.isReady())
                ? Health.up().build()
                : Health.down().build();
    }

    /** Stops claiming, lets running turns finish for a while, then removes the host. */
    @Override
    public void stop() {
        if (stopClaiming()) {
            drain();
        }
    }

    /**
     * Stops claiming on the calling thread, then drains on a thread of its own. Spring calls this for every bean of the
     * phase before it waits for their callbacks, so the review drain of the same phase runs alongside this one, and the
     * web server's phase still starts only after both ended.
     */
    @Override
    public void stop(Runnable callback) {
        if (!stopClaiming()) {
            callback.run();
            return;
        }
        Thread.ofPlatform().name("heph-turn-drain").start(() -> {
            try {
                drain();
            } catch (RuntimeException e) {
                log.warn("Heph drain failed: {}", e.getClass().getSimpleName());
            } finally {
                callback.run();
            }
        });
    }

    /** False when claiming had already stopped. */
    private synchronized boolean stopClaiming() {
        if (!active) {
            return false;
        }
        active = false;
        wake.release();
        return true;
    }

    private void drain() {
        long until = System.nanoTime() + drainTimeout.toNanos();
        // A claim that passed the active check before the stop ends its start first, so the turn it started is
        // waited for below, within the same deadline.
        Thread claiming = claimer;
        if (claiming != null && !claiming.equals(Thread.currentThread())) {
            try {
                claiming.join(Duration.ofNanos(until - System.nanoTime()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (running) {
            long remaining;
            while (!running.isEmpty() && (remaining = until - System.nanoTime()) > 0) {
                try {
                    TimeUnit.NANOSECONDS.timedWait(running, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (!running.isEmpty()) {
            log.warn(
                    "{} Heph turns still ran when this worker stopped; when their leases expire, a first attempt is"
                            + " queued once more and a second fails",
                    running.size());
        }
        host.shutdown();
    }

    @Override
    public boolean isRunning() {
        return active;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public void turnChanged(MentorLedgerEvents.TurnChanged change) {
        if (change.status() == MentorTurn.Status.RUNNING) {
            return;
        }
        Running turn = running.get(change.turnId());
        if (turn != null) {
            switch (change.status()) {
                case STOPPED -> stop(turn, "developer");
                // The lease reaper requeued the turn for its retry. The host stops the attempt that lost its lease
                // before the claim below can start the retry here. A retry this worker already claimed keeps
                // running. A turn the reaper failed shows as lost at the next renewal.
                case QUEUED -> {
                    if (turn.attempt == change.attempt()) {
                        lost(turn);
                    }
                }
                default -> {}
            }
        }
        // A new question, or an ended turn that unblocks the next one of its conversation.
        wake.release();
    }

    @Override
    public void resync() {
        wake.release();
        for (Running turn : running.values()) {
            try {
                if (turns.require(turn.workspaceId, turn.turnId).getStatus() == MentorTurn.Status.STOPPED) {
                    stop(turn, "developer");
                }
            } catch (RuntimeException e) {
                log.debug(
                        "Could not read Heph turn {} after reconnecting: {}",
                        turn.turnId,
                        e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Whether the attempt runs here. This worker holds its lease while it does: a lost lease, the end and a lost host
     * remove it.
     */
    boolean runs(UUID turnId, int attempt) {
        return running(turnId, attempt).isPresent();
    }

    /** The attempt, while it {@linkplain #runs runs} here. */
    Optional<TurnAttempt> running(UUID turnId, int attempt) {
        Running turn = running.get(turnId);
        return turn != null && turn.attempt == attempt ? Optional.of(turn.identity) : Optional.empty();
    }

    /**
     * When the attempt's turn ends: the deadline this worker stops it at. The turn credential lives longer, so work for
     * the attempt is bounded by this instead.
     *
     * @throws MentorLeaseLostException when the attempt does not run here
     */
    Instant deadline(UUID turnId, int attempt) {
        Running turn = running.get(turnId);
        if (turn == null || turn.attempt != attempt) {
            throw new MentorLeaseLostException(turnId, attempt);
        }
        return turn.deadline;
    }

    /**
     * Stores the host's newest partial answer of an attempt that runs here. The snapshot the attempt stored last
     * stores nothing, so a host that reports an unchanged answer costs no write.
     *
     * @throws MentorLeaseLostException when the attempt does not run here
     */
    void partial(UUID turnId, int attempt, JsonNode partial) {
        Running turn = running.get(turnId);
        if (turn == null || turn.attempt != attempt) {
            throw new MentorLeaseLostException(turnId, attempt);
        }
        JsonNode stored = turn.partial;
        if (partial.equals(stored)) {
            return;
        }
        completion.progress(turnId, attempt, workerId, partial, stored == null ? turn.claimed : null);
        turn.partial = partial;
    }

    /** The host ended the attempt through the turn endpoint; its slot is free. */
    void ended(UUID turnId, int attempt) {
        Running turn = running.get(turnId);
        if (turn != null && turn.attempt == attempt) {
            forget(turn);
        }
    }

    private void claimLoop() {
        while (active) {
            try {
                wake.tryAcquire(BACKSTOP.toMillis(), TimeUnit.MILLISECONDS);
                wake.drainPermits();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            while (active && running.size() < capacity && host.sandboxReady()) {
                if (!host.awaitReady(HOST_READY_TIMEOUT) || !active) {
                    break;
                }
                Optional<MentorTurnStarter.StartedTurn> started;
                try {
                    started = starter.startNext(workerId, lease);
                } catch (RuntimeException e) {
                    claimFailures.increment();
                    log.warn("Could not claim a Heph turn: {}", e.getClass().getSimpleName(), e);
                    break;
                }
                if (started.isEmpty()) {
                    break;
                }
                try {
                    begin(started.get());
                } catch (RuntimeException e) {
                    claimFailures.increment();
                    log.warn(
                            "Could not start Heph turn {}: {}",
                            started.get().turn().getId(),
                            e.getClass().getSimpleName(),
                            e);
                    Running claimed = running.get(started.get().turn().getId());
                    if (claimed != null) {
                        // The class only: the cause is logged at WARN when the end commits.
                        finishQuietly(
                                claimed,
                                MentorTurn.Status.FAILED,
                                COULD_NOT_START,
                                "starting it failed: " + e.getClass().getSimpleName());
                    }
                }
            }
        }
    }

    private void begin(MentorTurnStarter.StartedTurn started) {
        MentorTurn turn = started.turn();
        Instant now = clock.instant();
        Running attempt = new Running(
                turn, started.accountId(), now, now.plusSeconds(started.llm().timeoutSeconds()));
        Running earlier = running.put(attempt.turnId, attempt);
        if (earlier != null) {
            // This worker claimed the retry before it heard that the earlier attempt lost its lease: as for a lost
            // lease, its model calls are cancelled and its sandbox removed before its host is told to stop.
            callsInFlight.seal(earlier.turnId, earlier.attempt, true);
            sandboxes.release(earlier.turnId, earlier.attempt);
            stop(earlier, "lease");
        }
        Instant readyBy = clock.instant().plus(HOST_READY_TIMEOUT);
        if (!host.awaitReady(HOST_READY_TIMEOUT)) {
            // One fresh host, within the same wait, before the turn fails: a host can exit before its channel opens,
            // and the next one usually starts. The attempt sends its start once, to a ready host. A lease lost during
            // the wait marks the attempt stopped, so its host is told to stop right after the start.
            Duration left = Duration.between(clock.instant(), readyBy);
            boolean retry = running.get(attempt.turnId) == attempt && left.isPositive();
            if (retry)
                log.warn(
                        "Heph turn {} attempt {} waits for a fresh Heph host: the last one did not start",
                        attempt.turnId,
                        attempt.attempt);
            if (!retry || !host.awaitReady(left)) {
                finish(attempt, MentorTurn.Status.FAILED, COULD_NOT_START, "its Heph host did not start, twice");
                return;
            }
        }
        // A stop before this turn was registered here sent no notification this dispatcher acted on.
        if (turns.require(attempt.workspaceId, attempt.turnId).getStatus() == MentorTurn.Status.STOPPED) {
            finish(attempt, MentorTurn.Status.STOPPED, null, null);
            return;
        }
        String token = issuer.issueForAttempt(
                AttemptJwt.Kind.TURN,
                attempt.turnId,
                attempt.workspaceId,
                attempt.attempt,
                null,
                attempt.deadline.plus(stopGrace).plus(stopGrace));
        send(
                attempt,
                mapper.createObjectNode()
                        .put("type", "start")
                        .put("turnId", attempt.turnId.toString())
                        .put("attempt", attempt.attempt)
                        .put("token", token));
        attempt.started = true;
        // A stop that came while the turn started is sent after the start, which the host knows.
        String stopReason = attempt.stopReason;
        if (stopReason != null) {
            sendStop(attempt, stopReason);
        }
    }

    private void stop(Running turn, String reason) {
        if (turn.stopSent != null) {
            return;
        }
        turn.stopReason = reason;
        turn.stopSent = clock.instant();
        if (turn.started) {
            sendStop(turn, reason);
        }
    }

    private void sendStop(Running turn, String reason) {
        send(
                turn,
                mapper.createObjectNode()
                        .put("type", "stop")
                        .put("turnId", turn.turnId.toString())
                        .put("attempt", turn.attempt)
                        .put("reason", reason));
    }

    private void send(Running turn, ObjectNode frame) {
        try {
            host.send(mapper.writeValueAsString(frame));
        } catch (IOException e) {
            // The host is gone; the turn's lease expires and the reaper takes the turn back.
            log.warn(
                    "Could not reach the Heph host for turn {}: {}",
                    turn.turnId,
                    e.getClass().getSimpleName());
            forget(turn);
        }
    }

    /** Pings the host, renews leases, stops turns past their deadline, and ends turns their host did not end. */
    void tick() {
        host.ping();
        Instant now = clock.instant();
        for (Running turn : List.copyOf(running.values())) {
            try {
                Instant stopSent = turn.stopSent;
                if (stopSent != null && now.isAfter(stopSent.plus(stopGrace))) {
                    if (turn.overDeadline) {
                        finish(
                                turn,
                                MentorTurn.Status.FAILED,
                                TOOK_TOO_LONG,
                                "its Heph host did not end it within " + stopGrace + " after its deadline");
                    } else {
                        finish(turn, MentorTurn.Status.STOPPED, null, null);
                    }
                    continue;
                }
                if (stopSent == null && now.isAfter(turn.deadline)) {
                    turn.overDeadline = true;
                    stop(turn, "deadline");
                }
                if (Duration.between(turn.renewed, now).compareTo(lease.dividedBy(3)) >= 0) {
                    turns.renewLease(turn.turnId, turn.attempt, workerId, lease);
                    turn.renewed = now;
                }
            } catch (MentorLeaseLostException leaseLost) {
                lost(turn);
            } catch (RuntimeException e) {
                log.warn(
                        "Could not keep Heph turn {} alive: {}",
                        turn.turnId,
                        e.getClass().getSimpleName());
            }
        }
    }

    /**
     * The attempt lost its lease: it no longer counts here, and its host stops it without a word. Its model calls are
     * cancelled, as a stop cancels them, and its sandbox is removed.
     */
    private void lost(Running turn) {
        if (running.remove(turn.turnId, turn)) {
            log.info("Heph turn {} attempt {} lost its lease", turn.turnId, turn.attempt);
            callsInFlight.seal(turn.turnId, turn.attempt, true);
            sandboxes.release(turn.turnId, turn.attempt);
            released();
            stop(turn, "lease");
        }
    }

    /**
     * Ends a turn its host will not end, by the same path as the host's own end: the attempt's model calls are
     * cancelled, later ones refused, and the usage each call received is recorded before the turn is billed.
     *
     * @param cause why a failed turn failed, for the operator
     */
    private void finish(Running turn, MentorTurn.Status outcome, @Nullable String error, @Nullable String cause) {
        if (!callsInFlight.settle(turn.turnId, turn.attempt, true)) {
            log.warn(
                    "Heph turn {} attempt {} ended with a model call still in flight; that call may go unbilled",
                    turn.turnId,
                    turn.attempt);
        }
        try {
            completion.end(
                    turn.turnId,
                    turn.attempt,
                    workerId,
                    new MentorTurnCompletion.Ending(
                            outcome, List.of(), error, cause, callsInFlight.lastFailed(turn.turnId, turn.attempt)));
            callsInFlight.forgetFailed(turn.turnId, turn.attempt);
        } catch (MentorLeaseLostException alreadyEnded) {
            log.debug("Heph turn {} ended before this worker ended it", turn.turnId);
            callsInFlight.forgetFailed(turn.turnId, turn.attempt);
        } finally {
            forget(turn);
        }
    }

    private void finishQuietly(Running turn, MentorTurn.Status outcome, String error, String cause) {
        try {
            finish(turn, outcome, error, cause);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not end Heph turn {}; its lease is left to expire: {}",
                    turn.turnId,
                    e.getClass().getSimpleName());
        }
    }

    /** The attempt ended here: its slot is free, and its sandbox is removed with every file in it. */
    private void forget(Running turn) {
        if (running.remove(turn.turnId, turn)) {
            sandboxes.release(turn.turnId, turn.attempt);
            released();
        }
    }

    /** The host is gone: no attempt it ran may still call a model or use its sandbox. */
    private void hostLost() {
        running.values().forEach(turn -> {
            callsInFlight.seal(turn.turnId, turn.attempt, true);
            sandboxes.release(turn.turnId, turn.attempt);
        });
        running.clear();
        released();
    }

    private void released() {
        wake.release();
        synchronized (running) {
            running.notifyAll();
        }
    }
}
