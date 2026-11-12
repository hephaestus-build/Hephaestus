package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.ConversationToolsPods;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarter;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyCallsInFlight;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerDrainCoordinator;
import de.tum.cit.aet.hephaestus.mentor.MentorLeaseLostException;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorRedaction;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnProgress;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.Serial;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.JsonNode;

/**
 * The conversation turns of the installation, as every worker serves them to the conversation loop. A loop pulls each
 * assignment ({@link #assign}) from whichever worker its gateway Service picks, and every later call of the attempt may
 * reach another worker. So nothing here is held per worker: each call reads its attempt's row ({@link #held}), and the
 * row's lease is the only claim on a turn.
 *
 * <p>Control has no channel. A stop, a lost lease and an erasure seal the attempt's model calls in flight on every
 * worker ({@link #turnChanged}), and the attempt's next call answers {@code 409}. The loop renews the lease
 * ({@link #lease}); a lease call also ends an attempt that its loop did not end within {@link #STOP_GRACE} of a stop or
 * of its deadline. A loop that is gone renews nothing, and the lease reaper takes the turn back.
 * {@code docs/contributor/unified-pi-runtime.mdx} § Turn lifecycle has the protocol.
 */
public final class HephTurns implements MentorLedgerNotifications.Listener, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HephTurns.class);

    static final String TOOK_TOO_LONG = "Hephaestus took too long to reply and stopped. Try again.";
    static final String COULD_NOT_FINISH = "Hephaestus could not finish this reply. Try again.";
    static final String MODEL_STOPPED_ANSWERING =
            "Hephaestus's AI model stopped responding, so Hephaestus could not finish this reply. Try again in a moment.";

    /** The phase of the worker drain: this worker stops handing out turns with the review drain. */
    static final int PHASE = WorkerDrainCoordinator.PHASE;

    /** How long a loop has to end a turn after a stop or its deadline. */
    static final Duration STOP_GRACE = Duration.ofSeconds(30);

    /** How long an assignment pull waits for a turn before it answers that none waits. */
    static final Duration PULL_WAIT = Duration.ofSeconds(20);

    /** Why an attempt must stop: the developer stopped it, or its deadline passed. */
    public enum Stop {
        DEVELOPER,
        DEADLINE
    }

    /** An attempt that must stop: its next call answers {@code 409} with the reason. */
    public static final class StopException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final Stop reason;

        StopException(Stop reason) {
            super("The turn attempt must stop: " + reason.name().toLowerCase(Locale.ROOT), null, false, false);
            this.reason = reason;
        }

        public Stop reason() {
            return reason;
        }
    }

    /** This worker stops and hands out no more turns; the loop pulls from another worker. */
    public static final class DrainingException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        DrainingException() {
            super("This worker stops", null, false, false);
        }
    }

    /** One turn attempt for the loop: its credential, and when its work ends. */
    public record Assignment(UUID turnId, int attempt, String token, Instant deadline) {}

    private final MentorTurnStarter starter;
    private final MentorTurnService turns;
    private final MentorTurnRepository rows;
    private final MentorRedaction redaction;
    private final MentorTurnCompletion completion;
    private final MentorTurnProgress progress;
    private final ProxyCallsInFlight callsInFlight;
    private final ConversationToolsPods toolsPods;
    private final AttemptTokenIssuer issuer;
    private final Clock clock;
    private final int cap;
    private final Duration lease;
    private final Duration pullWait;
    private final Counter claimFailures;

    /** Pulls wait on it; every change of a turn counts {@link #changes} and wakes them. */
    private final Object turnChanges = new Object();

    private long changes;
    private volatile boolean active;

    public HephTurns(
            MentorTurnStarter starter,
            MentorTurnService turns,
            MentorTurnRepository rows,
            MentorRedaction redaction,
            MentorTurnCompletion completion,
            MentorTurnProgress progress,
            MentorLedgerNotifications notifications,
            ProxyCallsInFlight callsInFlight,
            ConversationToolsPods toolsPods,
            AttemptTokenIssuer issuer,
            Clock clock,
            MeterRegistry meters,
            int cap,
            Duration lease) {
        this(
                starter,
                turns,
                rows,
                redaction,
                completion,
                progress,
                notifications,
                callsInFlight,
                toolsPods,
                issuer,
                clock,
                meters,
                cap,
                lease,
                PULL_WAIT);
    }

    /** @param pullWait how long {@link #assign} waits for a turn; tests shorten it */
    HephTurns(
            MentorTurnStarter starter,
            MentorTurnService turns,
            MentorTurnRepository rows,
            MentorRedaction redaction,
            MentorTurnCompletion completion,
            MentorTurnProgress progress,
            MentorLedgerNotifications notifications,
            ProxyCallsInFlight callsInFlight,
            ConversationToolsPods toolsPods,
            AttemptTokenIssuer issuer,
            Clock clock,
            MeterRegistry meters,
            int cap,
            Duration lease,
            Duration pullWait) {
        this.starter = starter;
        this.turns = turns;
        this.rows = rows;
        this.redaction = redaction;
        this.completion = completion;
        this.progress = progress;
        this.callsInFlight = callsInFlight;
        this.toolsPods = toolsPods;
        this.issuer = issuer;
        this.clock = clock;
        this.cap = cap;
        this.lease = lease;
        this.pullWait = pullWait;
        this.claimFailures = Counter.builder(AgentMetrics.MENTOR_TURN_CLAIM_FAILURE)
                .description("conversation turns this worker could not claim or start because of an error")
                .register(meters);
        notifications.subscribe(this);
    }

    @Override
    public void start() {
        active = true;
    }

    /** Stops handing out turns. The turns that run go on: their calls reach the other workers. */
    @Override
    public void stop() {
        active = false;
        changed();
    }

    private void changed() {
        synchronized (turnChanges) {
            changes++;
            turnChanges.notifyAll();
        }
    }

    @Override
    public boolean isRunning() {
        return active;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /**
     * The next turn attempt for a loop that has room for one. Waits up to {@link #PULL_WAIT} for one, woken by every
     * change of a turn.
     *
     * @return empty when no turn may start now
     * @throws DrainingException when this worker stops
     * @throws RuntimeException when the claim failed
     */
    public Optional<Assignment> assign() {
        long until = System.nanoTime() + pullWait.toNanos();
        while (true) {
            if (!active) throw new DrainingException();
            long seen;
            synchronized (turnChanges) {
                seen = changes;
            }
            Optional<MentorTurnStarter.StartedTurn> started;
            try {
                started = starter.startNext(lease, cap);
            } catch (RuntimeException e) {
                claimFailures.increment();
                log.warn("Could not claim a conversation turn: {}", e.getClass().getSimpleName(), e);
                // The pull fails, so the loop waits before it pulls again.
                throw e;
            }
            if (started.isPresent()) {
                Optional<Assignment> assigned = begin(started.get());
                if (assigned.isPresent()) return assigned;
                continue;
            }
            synchronized (turnChanges) {
                while (changes == seen && active) {
                    long left = until - System.nanoTime();
                    if (left <= 0) return Optional.empty();
                    try {
                        turnChanges.wait(Math.max(1, left / 1_000_000));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return Optional.empty();
                    }
                }
            }
        }
    }

    private Optional<Assignment> begin(MentorTurnStarter.StartedTurn started) {
        MentorTurn turn = started.turn();
        UUID leaseId = Objects.requireNonNull(turn.getLeaseId());
        try {
            // A stop before the claim committed sent no notification that this pull heard.
            if (turns.require(turn.getWorkspaceId(), turn.getId()).getStatus() == MentorTurn.Status.STOPPED) {
                finish(
                        turn.getWorkspaceId(),
                        turn.getId(),
                        turn.getAttempt(),
                        leaseId,
                        MentorTurn.Status.STOPPED,
                        null,
                        null);
                return Optional.empty();
            }
            Instant deadline = clock.instant().plusSeconds(started.llm().timeoutSeconds());
            String token = issuer.issueForTurn(
                    turn.getId(),
                    turn.getWorkspaceId(),
                    turn.getAttempt(),
                    leaseId,
                    deadline,
                    deadline.plus(STOP_GRACE).plus(STOP_GRACE));
            return Optional.of(new Assignment(turn.getId(), turn.getAttempt(), token, deadline));
        } catch (RuntimeException e) {
            claimFailures.increment();
            log.warn(
                    "Could not start conversation turn {}: {}",
                    turn.getId(),
                    e.getClass().getSimpleName(),
                    e);
            finishQuietly(
                    turn.getWorkspaceId(),
                    turn.getId(),
                    turn.getAttempt(),
                    leaseId,
                    MentorTurn.Status.FAILED,
                    MentorTurnCompletion.COULD_NOT_START,
                    "starting it failed: " + e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * The attempt of a turn credential, while it holds its live lease: what its tools act for.
     *
     * @param ending whether the call ends the attempt or renews it, which a stop still allows
     * @throws MentorLeaseLostException when the attempt lost its lease or ended
     * @throws StopException when the attempt must stop, for a call that does not end it
     */
    public TurnAttempt held(AttemptJwt token, boolean ending) {
        MentorTurnRepository.HeldAttempt held = read(token);
        if (!ending) {
            if (stopped(held)) throw new StopException(Stop.DEVELOPER);
            if (clock.instant().isAfter(token.workDeadline())) throw new StopException(Stop.DEADLINE);
        }
        return attempt(token, held);
    }

    private MentorTurnRepository.HeldAttempt read(AttemptJwt token) {
        return rows.findHeldAttempt(token.workspaceId(), token.id(), token.attempt(), token.leaseId())
                .orElseThrow(() -> new MentorLeaseLostException(token.id(), token.attempt()));
    }

    private static boolean stopped(MentorTurnRepository.HeldAttempt held) {
        return MentorTurn.Status.STOPPED.name().equals(held.getStatus());
    }

    private static TurnAttempt attempt(AttemptJwt token, MentorTurnRepository.HeldAttempt held) {
        return new TurnAttempt(
                token.id(),
                token.workspaceId(),
                held.getAccountId(),
                held.getConversationId(),
                token.attempt(),
                token.leaseId());
    }

    /**
     * Renews the attempt's lease. It ends an attempt that its loop did not end within {@link #STOP_GRACE} after a stop
     * or after its deadline.
     *
     * @throws MentorLeaseLostException when the attempt lost its lease, ended, or ends now
     * @throws StopException when the attempt must stop; its lease is renewed, so that its loop can end it
     */
    public void lease(AttemptJwt token) {
        MentorTurnRepository.HeldAttempt held = read(token);
        TurnAttempt turn = attempt(token, held);
        Instant now = clock.instant();
        Instant startedAt = held.getStartedAt();
        // Every worker ends such an attempt when it hears of the erasure; this is the end if none of them could.
        if (startedAt != null && redaction.personErasedSince(startedAt)) {
            callsInFlight.seal(turn.turnId(), turn.attempt(), true);
            finish(turn, MentorTurn.Status.FAILED, COULD_NOT_FINISH, "context_changed");
            throw new MentorLeaseLostException(token.id(), token.attempt());
        }
        boolean stopped = stopped(held);
        Instant stoppedAt = held.getFinishedAt();
        if (stopped && stoppedAt != null && now.isAfter(stoppedAt.plus(STOP_GRACE))) {
            finish(turn, MentorTurn.Status.STOPPED, null, null);
            throw new MentorLeaseLostException(token.id(), token.attempt());
        }
        if (!stopped && now.isAfter(token.workDeadline().plus(STOP_GRACE))) {
            finish(
                    turn,
                    MentorTurn.Status.FAILED,
                    TOOK_TOO_LONG,
                    "its conversation loop did not end it within " + STOP_GRACE + " after its deadline");
            throw new MentorLeaseLostException(token.id(), token.attempt());
        }
        turns.renewLease(turn.workspaceId(), turn.turnId(), turn.attempt(), turn.leaseId(), lease);
        if (stopped) throw new StopException(Stop.DEVELOPER);
        if (now.isAfter(token.workDeadline())) throw new StopException(Stop.DEADLINE);
    }

    /** The partial answer of the attempt that this process last heard, for its end: readers may have seen it. */
    @Nullable
    JsonNode lastPartial(TurnAttempt turn) {
        return progress.snapshot(turn.workspaceId(), turn.turnId(), turn.attempt());
    }

    /** The attempt ended: its tools Pod goes with every file in it. */
    void ended(TurnAttempt turn) {
        toolsPods.ended(turn.turnId(), turn.attempt());
    }

    @Override
    public void turnChanged(MentorLedgerEvents.TurnChanged change) {
        // A stop, a requeue after a lost lease, and an end: the attempt's model calls through this worker end now, and
        // its next call learns why.
        if (change.status() != MentorTurn.Status.RUNNING && change.attempt() > 0) {
            callsInFlight.seal(change.turnId(), change.attempt(), true);
        }
        // A new question, or an ended turn that unblocks the next one of its conversation. A running turn's update,
        // such as each partial answer, frees no room.
        if (change.status() != MentorTurn.Status.RUNNING) {
            changed();
        }
    }

    /** The ledger's listener thread delivers every notification; an end can wait on the model calls it settles. */
    @Override
    public void contextChanged(MentorLedgerEvents.ContextChanged change) {
        Thread.ofVirtual().name("heph-turn-erasure").start(() -> endErased(change.workspaceId()));
    }

    @Override
    public void resync() {
        changed();
        Thread.ofVirtual().name("heph-turn-erasure").start(() -> endErased(null));
    }

    /**
     * Ends each leased attempt that runs on words a person took back since it started. Every worker hears the change;
     * the end is fenced on the lease, so one of them ends each attempt.
     */
    private void endErased(@Nullable Long workspaceId) {
        List<MentorTurnRepository.LeasedAttempt> leased;
        try {
            leased = rows.findLeasedAttempts(workspaceId);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not read the leased conversation turns: {}",
                    e.getClass().getSimpleName());
            return;
        }
        for (MentorTurnRepository.LeasedAttempt attempt : leased) {
            Instant startedAt = attempt.getStartedAt();
            if (startedAt == null || !redaction.personErasedSince(startedAt)) continue;
            callsInFlight.seal(attempt.getId(), attempt.getAttempt(), true);
            finishQuietly(
                    attempt.getWorkspaceId(),
                    attempt.getId(),
                    attempt.getAttempt(),
                    attempt.getLeaseId(),
                    MentorTurn.Status.FAILED,
                    COULD_NOT_FINISH,
                    "context_changed");
        }
    }

    private void finish(TurnAttempt turn, MentorTurn.Status outcome, @Nullable String error, @Nullable String cause) {
        finish(turn.workspaceId(), turn.turnId(), turn.attempt(), turn.leaseId(), outcome, error, cause);
    }

    /**
     * Ends an attempt that its loop will not end, by the same path as the loop's own end: the attempt's model calls are
     * cancelled, later ones refused, and the usage each call received is recorded before the turn is billed.
     *
     * @param cause why a failed turn failed, for the operator
     */
    private void finish(
            long workspaceId,
            UUID turnId,
            int attempt,
            UUID leaseId,
            MentorTurn.Status outcome,
            @Nullable String error,
            @Nullable String cause) {
        if (!callsInFlight.settle(turnId, attempt, true)) {
            log.warn(
                    "conversation turn {} attempt {} ended with a model call still in flight; that call may go unbilled",
                    turnId,
                    attempt);
        }
        try {
            completion.end(
                    workspaceId,
                    turnId,
                    attempt,
                    leaseId,
                    new MentorTurnCompletion.Ending(
                            outcome,
                            List.of(),
                            error,
                            cause,
                            callsInFlight.lastFailed(turnId, attempt),
                            progress.snapshot(workspaceId, turnId, attempt)));
        } catch (MentorLeaseLostException alreadyEnded) {
            log.debug("conversation turn {} ended before this worker ended it", turnId);
        } finally {
            callsInFlight.forgetFailed(turnId, attempt);
            toolsPods.ended(turnId, attempt);
        }
    }

    private void finishQuietly(
            long workspaceId,
            UUID turnId,
            int attempt,
            UUID leaseId,
            MentorTurn.Status outcome,
            @Nullable String error,
            @Nullable String cause) {
        try {
            finish(workspaceId, turnId, attempt, leaseId, outcome, error, cause);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not end conversation turn {}; its next lease call or the lease reaper ends it: {}",
                    turnId,
                    e.getClass().getSimpleName());
        }
    }
}
