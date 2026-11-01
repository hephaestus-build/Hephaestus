package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the measurement of each review job attempt ({@link ReviewAttemptUsage}) and its ledger rows, which are its
 * projection. The proxy registers a call before it forwards it and settles the call when it returns; a terminal
 * path settles the end of the attempt; an erasure removes a job's measurements. Each takes the job row first and
 * the attempt's row second.
 *
 * <p>A call that returns after its attempt ended, or after the job was handed to the next attempt, is settled on
 * its own attempt only. It never changes the job's status, output or feedback, nor the counters of the attempt that
 * runs now. The attempt's ledger rows are projected again from the whole measurement, with {@link
 * TerminalUsage#resolve} selecting the observed vector as at the terminal write. A late call therefore never adds to
 * a selected runner report: the proxy's total either still falls within the report, or it replaces it, or the two
 * are incomparable and the row becomes unverifiable.
 *
 * <p>Only a terminal path creates a measurement that no call registered, and only while it holds the job row on that
 * same attempt and no ledger row exists for it yet. A returned call never creates one, so an erased or historical
 * attempt stays as it is.
 */
@Service
public class ReviewAttemptUsageService {

    private static final Logger log = LoggerFactory.getLogger(ReviewAttemptUsageService.class);

    private final ReviewJobRepository jobs;
    private final ReviewAttemptUsageRepository attempts;
    private final LlmUsageRecorder recorder;

    public ReviewAttemptUsageService(
            ReviewJobRepository jobs, ReviewAttemptUsageRepository attempts, LlmUsageRecorder recorder) {
        this.jobs = jobs;
        this.attempts = attempts;
        this.recorder = recorder;
    }

    /** Where a returned call was settled. */
    public enum CallSettlement {
        /** On the attempt that still runs; the in-flight counters took it too. */
        CURRENT,
        /** On an attempt that already ended; its ledger rows were projected again. */
        LATE,
        /** Dropped: the job no longer exists in the call's workspace. */
        OWNER_GONE,
        /** Dropped: the call's attempt has no measurement, because it was never registered or was erased. */
        UNREGISTERED
    }

    /** What the end of an attempt settled. */
    public record Settled(TerminalUsage usage, boolean billed) {}

    /**
     * Registers a call before the proxy forwards it: creates the attempt's empty measurement and freezes the charge of
     * the call's model kind. Creating the measurement counts no call. Commits before the call leaves, so no lock is
     * held while the provider works.
     *
     * @return false when the attempt no longer runs on that worker; the call must not start
     */
    @Transactional
    public boolean registerCall(
            UUID jobId, long workspaceId, int attempt, UUID leaseId, ModelKind kind, FrozenCharge charge) {
        if (jobs.lockRunningAttemptForCall(jobId, workspaceId, attempt, leaseId).isEmpty()) return false;
        attempts.registerIfAbsent(jobId, attempt, workspaceId);
        attempts.freezeChargeIfAbsent(jobId, attempt, kind.name(), workspaceId, charge);
        return true;
    }

    /**
     * Settles one returned call on the attempt that authenticated it, in the caller's transaction. The practice
     * script row and the attempt's proxy total change together or not at all: a call that names no practice of the
     * job's workspace fails the whole settlement, as it did before.
     *
     * @param script the practice script call, {@code null} for the review's own call
     * @param usage the tokens of a call billed on the review's own row ({@code null} when the provider reported
     *     none, and for a practice script call of another model kind)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CallSettlement settleCall(
            UUID jobId,
            long workspaceId,
            int attempt,
            @Nullable PracticeScriptCallUsage script,
            @Nullable ReviewJobLlmUsageDelta usage) {
        ReviewJobRepository.UsageOwner job =
                jobs.lockUsageOwner(jobId, workspaceId).orElse(null);
        if (job == null) return CallSettlement.OWNER_GONE;
        ReviewAttemptUsage measured = attempts.lock(workspaceId, jobId, attempt).orElse(null);
        if (measured == null) return CallSettlement.UNREGISTERED;
        if (script != null && jobs.accumulatePracticeScriptUsage(jobId, attempt, script) == 0) {
            throw new IllegalStateException("The practice script call names no practice of the job's workspace");
        }
        boolean current =
                job.getAttempt() == attempt && ReviewJobStatus.RUNNING.name().equals(job.getStatus());
        if (usage != null) {
            measured.addProxyCall(usage);
            if (current) jobs.accumulateLlmUsage(jobId, attempt, usage);
        }
        if (current) return CallSettlement.CURRENT;
        project(measured, job.getReviewId(), Map.of(), Instant.now());
        return CallSettlement.LATE;
    }

    /**
     * Creates the measurement of an attempt that still runs, before the caller hands the job to its next attempt in
     * the same transaction: a requeue zeroes the counters and the next claim replaces the configuration, so after it
     * nothing of this attempt could be recovered from the row. Does nothing when the attempt already has one.
     *
     * @param job the attempt as the caller locked it
     * @param admitted the charges of the attempt's own configuration
     * @param proxyCounts the job row's counters, read while it is still on this attempt
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void holdAttempt(
            ReviewJob job, Map<ModelKind, FrozenCharge> admitted, @Nullable ReviewJobLlmUsage proxyCounts) {
        ReviewJobRepository.UsageOwner state =
                jobs.lockUsageOwner(job.getId(), job.getWorkspaceId()).orElse(null);
        if (state == null
                || state.getAttempt() != job.getAttempt()
                || !ReviewJobStatus.RUNNING.name().equals(state.getStatus())) return;
        long workspaceId = job.getWorkspaceId();
        ReviewAttemptUsage measured =
                attempts.lock(workspaceId, job.getId(), job.getAttempt()).orElse(null);
        if (measured == null) {
            if (alreadyBilled(job)) return;
            attempts.save(ReviewAttemptUsage.startedFrom(job, proxyCounts));
        }
        admitted.forEach((kind, charge) ->
                attempts.freezeChargeIfAbsent(job.getId(), job.getAttempt(), kind.name(), workspaceId, charge));
    }

    /**
     * Settles the end of an attempt in the terminal transaction: keeps the runner's report, dates the attempt once,
     * and projects its ledger rows. A repeated settlement of the same attempt keeps the first report and projects
     * the same rows again.
     *
     * <p>Without a measurement, one is created only while the caller holds the job row on this same attempt and no
     * ledger row exists for it: then the row's counters and configuration are still this attempt's. Otherwise the
     * attempt is left as it is and nothing is written.
     *
     * @param admitted the charges of the configuration the attempt ends with; a charge the proxy already froze wins
     * @param runnerUsage the runner's report, {@code null} on every path without a clean finish
     * @param proxyCounts the job row's counters as the attempt left them; read only to create a measurement
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Settled settleAttempt(
            ReviewJob job,
            Map<ModelKind, FrozenCharge> admitted,
            AgentResult.@Nullable LlmUsage runnerUsage,
            @Nullable ReviewJobLlmUsage proxyCounts) {
        long workspaceId = job.getWorkspaceId();
        ReviewJobRepository.UsageOwner state =
                jobs.lockUsageOwner(job.getId(), workspaceId).orElse(null);
        ReviewAttemptUsage measured =
                attempts.lock(workspaceId, job.getId(), job.getAttempt()).orElse(null);
        if (measured == null) {
            if (state == null || state.getAttempt() != job.getAttempt() || alreadyBilled(job)) {
                log.warn(
                        "No usage measurement to settle for job {} attempt {}; its ledger stays as it is",
                        job.getId(),
                        job.getAttempt());
                return new Settled(TerminalUsage.none(), false);
            }
            measured = ReviewAttemptUsage.startedFrom(job, proxyCounts);
            attempts.save(measured);
        }
        measured.recordRunnerUsage(runnerUsage);
        return project(measured, job.getReviewId(), admitted, Instant.now());
    }

    /**
     * Removes every measurement of a job that an erasure selected, after its cancellation and before the ledger is
     * anonymized. A call that returns afterwards finds no measurement and is dropped, so it cannot write the erased
     * review back into the ledger.
     */
    @Transactional
    public void forgetJob(long workspaceId, UUID jobId) {
        jobs.lockUsageOwner(jobId, workspaceId);
        attempts.deleteCharges(workspaceId, jobId);
        attempts.deleteMeasurements(workspaceId, jobId);
    }

    private boolean alreadyBilled(ReviewJob job) {
        return recorder.isReviewAttemptRecorded(job.getWorkspaceId(), job.getId(), job.getAttempt());
    }

    private Settled project(
            ReviewAttemptUsage measured, UUID reviewId, Map<ModelKind, FrozenCharge> admitted, Instant now) {
        UUID jobId = measured.getId().getJobId();
        int attempt = measured.getId().getAttempt();
        long workspaceId = measured.getWorkspaceId();
        Map<ModelKind, FrozenCharge> charges = new EnumMap<>(ModelKind.class);
        for (ReviewAttemptCharge charge : attempts.findCharges(workspaceId, jobId, attempt)) {
            charges.put(charge.modelKind(), charge.charge());
        }
        admitted.forEach((kind, charge) -> {
            if (charges.containsKey(kind)) return;
            attempts.freezeChargeIfAbsent(jobId, attempt, kind.name(), workspaceId, charge);
            charges.put(kind, charge);
        });
        List<PracticeScriptKindTotal> practiceScript = jobs.sumPracticeScriptUsageByKind(workspaceId, jobId, attempt);
        TerminalUsage spent = TerminalUsage.resolve(measured.runnerUsage(), measured.proxyUsage(), practiceScript);
        boolean billed = spent.projectTo(
                recorder,
                new TerminalUsage.Source(workspaceId, reviewId, jobId, attempt, measured.occurredAt(now)),
                charges,
                practiceScript);
        return new Settled(spent, billed);
    }
}
