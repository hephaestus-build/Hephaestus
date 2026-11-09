package de.tum.cit.aet.hephaestus.agent.run;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
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

    AgentRuns(AgentRunRepository runs, AgentCapacityProperties capacity) {
        this.runs = runs;
        this.capacity = capacity;
    }

    /** The capacity numbers that leases and their kinds' attempt budgets follow. */
    public AgentCapacityProperties capacity() {
        return capacity;
    }

    /** Queues a new run of a subject. Claimers hear of it after the transaction commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AgentRunRef enqueue(
            long workspaceId, AgentRunKind kind, AgentRunBand band, String concurrencyKey, @Nullable Instant deadline) {
        return insert(workspaceId, kind, band, concurrencyKey, 0, 0, deadline);
    }

    /**
     * Queues the run that continues work whose earlier run ended. Its epochs go on from {@code lastEpoch}, so the
     * claims of a piece of work keep distinct numbers across its runs. The continuation spends one attempt more than
     * the ended run had spent: it is a retry of the same work, so the work's retry limit bounds how often it continues.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AgentRunRef continueAfter(AgentRunRef ended, int lastEpoch) {
        AgentRun previous = runs.findByRef(ended.id(), ended.workspaceId())
                .orElseThrow(() -> new IllegalStateException("The run to continue does not exist"));
        if (previous.getState() != AgentRunState.ENDED)
            throw new IllegalStateException("Only an ended run is continued");
        return insert(
                previous.getWorkspaceId(),
                previous.getKind(),
                previous.getBand(),
                previous.getConcurrencyKey(),
                Math.max(lastEpoch, previous.getLeaseEpoch()),
                previous.getAttempt() + 1,
                null);
    }

    private AgentRunRef insert(
            long workspaceId,
            AgentRunKind kind,
            AgentRunBand band,
            String concurrencyKey,
            int epoch,
            int spent,
            @Nullable Instant deadline) {
        AgentRun run = new AgentRun(
                UUID.randomUUID(),
                workspaceId,
                kind,
                band,
                concurrencyKey,
                epoch,
                spent,
                runs.databaseClock(),
                deadline);
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
        if (kinds.isEmpty()) return Optional.empty();
        // Only the oldest queued run of a subject is offered, and a claim skips a locked one, so two claims never lease
        // two runs of one subject; the partial unique index on the leased subject refuses it if they did.
        return runs.claim(
                        kinds.stream().map(Enum::name).toList(),
                        excluded,
                        capacity.leaseTtl().toSeconds(),
                        capacity.bandAging().toSeconds())
                .map(AgentRuns::lease);
    }

    /** Extends the lease; empty when the holder no longer holds it, after which it must not write. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Instant> renew(AgentRunFence fence) {
        return runs.renew(
                fence.runId(),
                fence.workspaceId(),
                fence.epoch(),
                capacity.leaseTtl().toSeconds());
    }

    /**
     * Puts the run back in the queue. A charged requeue spends one attempt and waits the backoff of the attempts spent.
     *
     * @return whether the holder still held the run
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean requeue(AgentRunFence fence, Charge charge, Duration delay) {
        int spent = charge == Charge.SPEND ? 1 : 0;
        boolean requeued = runs.requeue(fence.runId(), fence.workspaceId(), fence.epoch(), spent, seconds(delay)) == 1;
        if (requeued) wake(fence);
        return requeued;
    }

    /** Ends the run for its holder; whether the holder still held it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean end(AgentRunFence fence) {
        return runs.end(fence.runId(), fence.workspaceId(), fence.epoch(), AgentRunStopReason.COMPLETED.name()) == 1;
    }

    /**
     * Ends the run for someone other than its holder. A holder's next fenced write then changes nothing.
     *
     * @return whether the run was still open
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancel(AgentRunRef run) {
        return runs.cancel(run.id(), run.workspaceId()) == 1;
    }

    /** Ends these runs for someone other than their holders, as a fleet-wide capacity verdict does; how many ended. */
    @WorkspaceAgnostic("A fleet-wide verdict ends the runs it selected in every workspace")
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelAll(Collection<UUID> runIds) {
        return runIds.isEmpty() ? 0 : runs.cancelAll(runIds);
    }

    /** Offers a queued run no earlier than {@code delay} from now. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean defer(AgentRunRef run, Duration delay) {
        return runs.defer(run.id(), run.workspaceId(), seconds(delay)) == 1;
    }

    /** Offers these queued runs of the workspace now: the hold that delayed them was lifted. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int releaseAll(long workspaceId, Collection<UUID> runIds) {
        if (runIds.isEmpty()) return 0;
        int released = runs.releaseAll(workspaceId, runIds);
        if (released > 0)
            runs.findByRef(runIds.iterator().next(), workspaceId).ifPresent(found -> wake(found.getKind()));
        return released;
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
