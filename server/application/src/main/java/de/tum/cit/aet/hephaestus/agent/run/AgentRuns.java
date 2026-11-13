package de.tum.cit.aet.hephaestus.agent.run;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one lease model of agent work: enqueue, claim, renew, requeue, end and cancel. Every method joins the caller's
 * transaction, so the domain row and its run move together or not at all.
 */
@Service
public class AgentRuns {

    /** The longest wait before a requeued run is offered again. */
    static final Duration BACKOFF_CAP = Duration.ofMinutes(15);

    private static final long BACKOFF_OFFSET_SECONDS = 15;
    private static final double BACKOFF_JITTER = 0.10;

    private final AgentRunRepository runs;
    private final AgentCapacityProperties capacity;
    private final MeterRegistry meters;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    AgentRuns(AgentRunRepository runs, AgentCapacityProperties capacity, MeterRegistry meters) {
        this.runs = runs;
        this.capacity = capacity;
        this.meters = meters;
    }

    /** The capacity numbers that leases and their kinds' attempt budgets follow. */
    public AgentCapacityProperties capacity() {
        return capacity;
    }

    /** The in-flight review work of the single server, including feedback composition. */
    @Transactional(readOnly = true)
    @WorkspaceAgnostic("Single-server shutdown counts leased reviews across workspaces")
    public int leasedReviews() {
        return runs.countLeased(AgentRunKind.REVIEW.name()) + runs.countLeased(AgentRunKind.REVIEW_COMPOSE.name());
    }

    /** Queues a new run of a subject. Claimers hear of it after the transaction commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AgentRunRef enqueue(
            long workspaceId,
            AgentRunKind kind,
            AgentRunBand band,
            String concurrencyKey,
            @Nullable Duration startWithin) {
        return insert(workspaceId, kind, band, concurrencyKey, 0, 0, startWithin);
    }

    /**
     * Queues the run that continues work whose earlier run ended. Its epochs go on from {@code lastEpoch}, so the
     * claims of a piece of work keep distinct numbers across its runs. The continuation spends one attempt more than
     * the ended run had spent: it is a retry of the same work, so the work's retry limit bounds how often it continues.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AgentRunRef continueAfter(AgentRunRef ended, int lastEpoch, Duration delay, Duration executionWindow) {
        AgentRun previous = ended(ended);
        return insert(
                previous.getWorkspaceId(),
                previous.getKind(),
                previous.getBand(),
                previous.getConcurrencyKey(),
                Math.max(lastEpoch, previous.getLeaseEpoch()),
                previous.getAttempt() + 1,
                startWithin(previous),
                delay,
                executionWindow);
    }

    /**
     * Queues the next step of work whose earlier run ended, as a run of another kind: a review's composition after its
     * measurement. Its epochs go on from {@code lastEpoch}, as a continuation's do. It is no retry, so it spends no
     * attempt: the work's attempts count across both steps.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AgentRunRef continueAs(AgentRunRef ended, int lastEpoch, AgentRunKind kind, Duration executionWindow) {
        AgentRun previous = ended(ended);
        if (previous.getKind() == kind) throw new IllegalArgumentException("A run of the same kind continues it");
        return insert(
                previous.getWorkspaceId(),
                kind,
                previous.getBand(),
                previous.getConcurrencyKey(),
                Math.max(lastEpoch, previous.getLeaseEpoch()),
                previous.getAttempt(),
                null,
                Duration.ZERO,
                executionWindow);
    }

    /**
     * Replaces an expired lease and settles its open calls. The caller relinks the domain in this same transaction.
     * The conditional end is the fence winner; a renewal or another reaper rolls the whole continuation back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AgentRunRef continueExpired(AgentRunFence fence, Duration delay, Duration executionWindow) {
        if (runs.endExpiredForContinuation(fence.runId(), fence.workspaceId(), fence.epoch()) != 1)
            throw new StaleRunException(fence);
        int calls = runs.settleOpenCalls(fence.runId(), fence.workspaceId(), fence.epoch());
        TransactionCallbacks.afterCommit(() -> settled(calls));
        AgentRun previous = ended(fence.run());
        return insert(
                previous.getWorkspaceId(),
                previous.getKind(),
                previous.getBand(),
                previous.getConcurrencyKey(),
                previous.getLeaseEpoch(),
                previous.getAttempt(),
                startWithin(previous),
                delay,
                executionWindow);
    }

    private static @Nullable Duration startWithin(AgentRun previous) {
        return previous.getStartWithinSeconds() == null ? null : Duration.ofSeconds(previous.getStartWithinSeconds());
    }

    private AgentRun ended(AgentRunRef ended) {
        AgentRun previous = runs.findByRef(ended.id(), ended.workspaceId())
                .orElseThrow(() -> new IllegalStateException("The run to continue does not exist"));
        // The native end keeps domain entities managed. Refresh only this run before inspecting its state.
        Objects.requireNonNull(entityManager).refresh(previous);
        if (previous.getState() != AgentRunState.ENDED)
            throw new IllegalStateException("Only an ended run is continued");
        return previous;
    }

    private AgentRunRef insert(
            long workspaceId,
            AgentRunKind kind,
            AgentRunBand band,
            String concurrencyKey,
            int epoch,
            int spent,
            @Nullable Duration startWithin) {
        return insert(workspaceId, kind, band, concurrencyKey, epoch, spent, startWithin, Duration.ZERO, null);
    }

    private AgentRunRef insert(
            long workspaceId,
            AgentRunKind kind,
            AgentRunBand band,
            String concurrencyKey,
            int epoch,
            int spent,
            @Nullable Duration startWithin,
            Duration delay,
            @Nullable Duration executionWindow) {
        AgentRun run = new AgentRun(
                UUID.randomUUID(),
                workspaceId,
                kind,
                band,
                concurrencyKey,
                epoch,
                spent,
                runs.databaseClock(),
                startWithin,
                delay,
                executionWindow);
        runs.save(run);
        wake(kind);
        return run.ref();
    }

    /**
     * Leases the next run of these kinds that the claim order offers, or none.
     *
     * @param excluded runs this claimer already declined in this round
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AgentRunLease> claim(Set<AgentRunKind> kinds, Collection<UUID> excluded) {
        return claimNext(kinds, excluded);
    }

    private Optional<AgentRunLease> claimNext(Set<AgentRunKind> kinds, Collection<UUID> excluded) {
        if (kinds.isEmpty()) return Optional.empty();
        // Only the oldest queued run of a subject is offered, and a claim skips a locked one, so two claims never lease
        // two runs of one subject; the partial unique index on the leased subject refuses it if they did.
        return runs.claim(
                        kinds.stream().map(Enum::name).toList(),
                        excluded,
                        capacity.leaseTtl().toSeconds(),
                        capacity.turnLeaseTtl().toSeconds(),
                        capacity.bandAging().toSeconds())
                .map(AgentRuns::lease);
    }

    /**
     * Leases the next run of one kind that the claim order offers, unless {@code cap} runs of the kind are leased
     * already. Concurrent claims of the kind take one lock in turn, so the cap holds across workers.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AgentRunLease> claimCapped(AgentRunKind kind, int cap) {
        runs.lockKindClaims(kind.conversation() ? AgentRunKind.TURN.name() : kind.name());
        if (countLeased(kind) >= cap) return Optional.empty();
        return claimNext(Set.of(kind), List.of());
    }

    private long countLeased(AgentRunKind kind) {
        return kind.conversation()
                ? runs.countLeased(AgentRunKind.TURN.name()) + runs.countLeased(AgentRunKind.DIGEST.name())
                : runs.countLeased(kind.name());
    }

    /**
     * Leases the next run of these kinds of one subject, for the sandbox that serves only that subject, or none. The
     * claim order and the rule of one leased run for each subject are the same as for {@link #claim}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AgentRunLease> claimSubject(long workspaceId, String concurrencyKey, Set<AgentRunKind> kinds) {
        if (kinds.isEmpty()) return Optional.empty();
        Set<AgentRunKind> offered = kinds;
        Integer turnCap = capacity.turns();
        if (kinds.stream().anyMatch(AgentRunKind::conversation)
                && turnCap != null
                && (!runs.tryLockKindClaims(AgentRunKind.TURN.name()) || countLeased(AgentRunKind.TURN) >= turnCap)) {
            offered = EnumSet.copyOf(kinds);
            offered.removeIf(AgentRunKind::conversation);
            if (offered.isEmpty()) return Optional.empty();
        }
        return runs.claimSubject(
                        workspaceId,
                        concurrencyKey,
                        offered.stream().map(Enum::name).toList(),
                        capacity.leaseTtl().toSeconds(),
                        capacity.turnLeaseTtl().toSeconds(),
                        capacity.bandAging().toSeconds())
                .map(AgentRuns::lease);
    }

    /** A queued run that its sandbox claims next. */
    public record DueRun(AgentRunRef run, AgentRunKind kind, String concurrencyKey) {}

    /**
     * The queued runs of these kinds that a claim would offer next, at most {@code limit}, in the claim's order. It
     * leases nothing: the dispatch starts their sandboxes, and each sandbox claims its own run.
     */
    @Transactional(readOnly = true)
    public List<DueRun> due(Set<AgentRunKind> kinds, int limit) {
        if (kinds.isEmpty() || limit <= 0) return List.of();
        return runs
                .findDue(
                        kinds.stream().map(AgentRunKind::name).toList(),
                        limit,
                        capacity.bandAging().toSeconds())
                .stream()
                .map(row -> new DueRun(
                        new AgentRunRef(row.getId(), row.getWorkspaceId()),
                        AgentRunKind.valueOf(row.getKind()),
                        row.getConcurrencyKey()))
                .toList();
    }

    /** The run of a workspace, in whatever state; locks nothing. */
    @Transactional(readOnly = true)
    public Optional<AgentRun> find(AgentRunRef run) {
        return runs.findByRef(run.id(), run.workspaceId());
    }

    /** Extends the lease; empty when the holder no longer holds it, after which it must not write. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Instant> renew(AgentRunFence fence) {
        return runs.renew(
                fence.runId(),
                fence.workspaceId(),
                fence.epoch(),
                capacity.leaseTtl().toSeconds(),
                capacity.turnLeaseTtl().toSeconds());
    }

    /**
     * Puts the run back in the queue. A charged requeue spends one attempt and waits the backoff of the attempts spent.
     * The model calls that the claim left open are settled with it.
     *
     * @param hold why it waits beyond a retry's backoff, or {@code null}
     * @return whether the holder still held the run
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean requeue(AgentRunFence fence, Charge charge, Duration delay, @Nullable AgentRunHold hold) {
        int spent = charge == Charge.SPEND ? 1 : 0;
        boolean requeued = runs.requeue(
                        fence.runId(),
                        fence.workspaceId(),
                        fence.epoch(),
                        spent,
                        seconds(delay),
                        hold == null ? null : hold.name())
                == 1;
        if (requeued) {
            settled(runs.settleOpenCalls(fence.runId(), fence.workspaceId(), fence.epoch()));
            wake(fence);
        }
        return requeued;
    }

    /**
     * Ends the run for its holder, and settles the model calls that the claim left open. A holder whose domain end
     * already ended the run under the same claim, as a turn's end does, reads {@link EndOutcome#ALREADY_ENDED}: its end
     * arrived twice, not late.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EndOutcome end(AgentRunFence fence) {
        if (runs.end(fence.runId(), fence.workspaceId(), fence.epoch(), AgentRunStopReason.COMPLETED.name()) == 1) {
            settled(runs.settleOpenCalls(fence.runId(), fence.workspaceId(), fence.epoch()));
            return EndOutcome.ENDED;
        }
        return runs.endedBy(fence.runId(), fence.workspaceId(), fence.epoch())
                ? EndOutcome.ALREADY_ENDED
                : EndOutcome.STALE;
    }

    /** How a holder's end came out. */
    public enum EndOutcome {
        /** This call ended the run. */
        ENDED,
        /** The holder's claim had already ended the run: the call repeats an end. */
        ALREADY_ENDED,
        /** The holder no longer held the run; it must not write. */
        STALE
    }

    /**
     * Brings the run's deadline forward to {@code by}, or sets it when it had none. A deadline never moves later, so a
     * holder cannot outlive what bounded it. A leased run is renewed only until then, and the reaper ends it there.
     *
     * @return whether the run was still open
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean endBy(AgentRunRef run, Instant by) {
        return runs.endBy(run.id(), run.workspaceId(), by) == 1;
    }

    /**
     * {@link #endBy} for the claim that still holds the run. A claim that lost its run moves no deadline: a requeued
     * review or turn starts its next claim without one. A composition keeps its creation deadline.
     *
     * @return whether the claim still holds the run
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean limit(AgentRunFence claim, Instant by) {
        return runs.limit(claim.runId(), claim.workspaceId(), claim.epoch(), by) == 1;
    }

    /**
     * Moves the workspace's queued runs of a kind from one subject to another, as when two subjects merge. A leased run
     * keeps its subject until it ends.
     *
     * @return how many runs moved
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int moveQueued(long workspaceId, AgentRunKind kind, String from, String to) {
        return runs.moveQueued(workspaceId, kind.name(), from, to);
    }

    /**
     * Ends the run for someone other than its holder, and settles the model calls it left open. A holder's next fenced
     * write then changes nothing.
     *
     * @return whether the run was still open
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancel(AgentRunRef run) {
        if (runs.cancel(run.id(), run.workspaceId()) != 1) return false;
        settled(runs.settleOpenCallsOfRun(run.id(), run.workspaceId()));
        return true;
    }

    /**
     * Ends these runs for someone other than their holders, as a fleet-wide capacity verdict does, and settles the model
     * calls they left open; how many ended.
     */
    @WorkspaceAgnostic("A fleet-wide verdict ends the runs it selected in every workspace")
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelAll(Collection<UUID> runIds) {
        if (runIds.isEmpty()) return 0;
        int cancelled = runs.cancelAll(runIds);
        settled(runs.settleOpenCallsOfRuns(runIds));
        return cancelled;
    }

    /** Counts the calls that a claim's end settled without a measurement. */
    private void settled(int calls) {
        if (calls > 0) meters.counter(AgentMetrics.LLM_USAGE_UNVERIFIABLE).increment(calls);
    }

    /** Holds a queued run for {@code reason}, and offers it again no earlier than {@code delay} from now. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hold(AgentRunRef run, AgentRunHold reason, Duration delay) {
        return runs.hold(run.id(), run.workspaceId(), reason.name(), seconds(delay)) == 1;
    }

    /**
     * Offers now every queued run of the workspace that {@code reason} held, of every kind: the cause was lifted. A
     * retry's backoff is no hold, so it keeps its wait.
     *
     * @return how many runs were released
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int releaseHolds(long workspaceId, AgentRunHold reason) {
        List<String> released = runs.releaseHolds(workspaceId, reason.name());
        released.stream().distinct().map(AgentRunKind::valueOf).forEach(this::wake);
        return released.size();
    }

    /**
     * Holds the lease until the caller's transaction ends, so no requeue, end or reap can take it meanwhile. Every
     * fenced domain write calls this first.
     *
     * @throws StaleRunException when {@code fence} no longer holds the run
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCurrent(AgentRunFence fence) {
        if (runs.lockCurrent(fence.runId(), fence.workspaceId(), fence.epoch()).isEmpty())
            throw new StaleRunException(fence);
    }

    /** Whether the claim numbered {@code epoch} holds {@code run} now; never for an epoch before the first claim. */
    @Transactional(readOnly = true)
    public boolean isCurrent(AgentRunRef run, int epoch) {
        return epoch >= 1
                && runs.findCurrent(run.id(), run.workspaceId(), epoch).isPresent();
    }

    /** Deletes an ended run that no domain row points to any more, as after {@link #continueAfter}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retire(AgentRunRef ended) {
        runs.deleteEnded(ended.id(), ended.workspaceId());
    }

    /** Deletes ended runs whose domain rows retention deleted; a run that is not ended stays. */
    @WorkspaceAgnostic("Retention deletes the ended runs of the domain rows it deleted, in every workspace")
    @Transactional(propagation = Propagation.MANDATORY)
    public int retireAll(Collection<UUID> runIds) {
        if (runIds.isEmpty()) return 0;
        return runs.deleteAllEndedById(runIds);
    }

    /**
     * Deletes these runs of the workspace whatever their state, after an erasure deleted the domain rows that pointed
     * to them: no row is left for a run to drive, and a holder's next fenced write finds its run gone.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int forget(long workspaceId, Collection<UUID> runIds) {
        if (runIds.isEmpty()) return 0;
        return runs.deleteAllInWorkspace(workspaceId, runIds);
    }

    /** Deletes the workspace's runs, after the domain rows that point to them. */
    @Transactional
    public int deleteWorkspace(long workspaceId) {
        return runs.deleteAllByWorkspaceId(workspaceId);
    }

    /** The wait before the next offer of a run that spent {@code spentAttempts}, with jitter. */
    public static Duration backoff(int spentAttempts) {
        return backoff(spentAttempts, ThreadLocalRandom.current());
    }

    static Duration backoff(int spentAttempts, RandomGenerator random) {
        // Clamped before the power: n^4 overflows a long for a large attempt count.
        long n = Math.min(Math.max(0, spentAttempts), 1000);
        long base = n * n * n * n + BACKOFF_OFFSET_SECONDS;
        double jitter = 1.0 + (random.nextDouble() * 2.0 - 1.0) * BACKOFF_JITTER;
        // Jittered before the cap, so the upward leg cannot pass it.
        long seconds = Math.min(Math.round(base * jitter), BACKOFF_CAP.toSeconds());
        return Duration.ofSeconds(Math.max(1, seconds));
    }

    /** Whether a requeue spends one of the kind's attempts. */
    public enum Charge {
        SPEND,
        FREE
    }

    private void wake(AgentRunFence fence) {
        runs.findByRef(fence.runId(), fence.workspaceId()).ifPresent(run -> wake(run.getKind()));
    }

    private void wake(AgentRunKind kind) {
        runs.notifyQueued(AgentRunNotifications.CHANNEL, kind.name());
    }

    private static long seconds(Duration delay) {
        return Math.max(0, delay.toSeconds());
    }

    private static AgentRunLease lease(AgentRunRepository.LeaseRow row) {
        return new AgentRunLease(
                row.getId(),
                row.getWorkspaceId(),
                AgentRunKind.valueOf(row.getKind()),
                row.getConcurrencyKey(),
                row.getAttempt(),
                row.getLeaseEpoch(),
                row.getLeasedUntil(),
                row.getDeadline());
    }
}
