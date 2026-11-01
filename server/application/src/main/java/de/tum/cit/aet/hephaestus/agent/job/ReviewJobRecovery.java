package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Recovers lost attempts without repeating admitted results or losing their measured spend. */
@Component
@ConditionalOnCaptureRole
@WorkspaceAgnostic("Recovery uses the supplied job id, lease, and attempt fence")
public class ReviewJobRecovery {
    enum Outcome {
        NONE,
        REQUEUED,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private static final Logger log = LoggerFactory.getLogger(ReviewJobRecovery.class);
    private final ReviewJobRepository jobs;
    private final AgentProperties properties;
    private final ObjectMapper mapper;
    private final ReviewAttemptUsageService usage;
    private final Optional<ReviewCompositionContinuation> continuation;

    public ReviewJobRecovery(
            ReviewJobRepository jobs,
            AgentProperties properties,
            ObjectMapper mapper,
            ReviewAttemptUsageService usage,
            Optional<ReviewCompositionContinuation> continuation) {
        this.jobs = jobs;
        this.properties = properties;
        this.mapper = mapper;
        this.usage = usage;
        this.continuation = continuation;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome recover(UUID jobId, UUID leaseId, int attempt, @Nullable ReviewJobCancellationReason cancellation) {
        continuation.ifPresent(ReviewCompositionContinuation::holdRecoveryCustody);
        ReviewJob job = jobs.lockById(jobId).orElse(null);
        if (!owns(job, leaseId, attempt)) return Outcome.NONE;
        ReviewJob owned = Objects.requireNonNull(job);
        ReviewJobLlmUsage counts = counts(owned);
        hold(owned, counts);
        Instant now = Instant.now();
        boolean admitted = ResultAdmissionService.isAdmitted(owned);
        if (drained(cancellation) && !admitted) {
            // Not the review's fault: the next attempt spends no retry, up to ReviewJob.MAX_DRAINS drains.
            if (jobs.requeueDrained(jobId, leaseId, ReviewJob.MAX_DRAINS, now) > 0) {
                bill(owned, counts);
                return Outcome.REQUEUED;
            }
            int cancelled = jobs.transitionToCancelledOwnedBy(
                    jobId, now, DRAINED_TOO_OFTEN, Objects.requireNonNull(cancellation), leaseId, attempt);
            if (cancelled == 0) return Outcome.NONE;
            bill(owned, counts);
            return Outcome.CANCELLED;
        }
        Instant availableAt = now.plus(ReviewJobBackoff.compute(owned.spentAttempts() + 1));
        int changed = owned.spentAttempts() < properties.maxRetries()
                ? jobs.requeueOrphan(jobId, leaseId, properties.maxRetries(), availableAt)
                : 0;
        // An admitted review is handed on even at the retry limit: the attempt after it composes nothing and only
        // withholds what is still open.
        if (changed == 0 && admitted && continuation.isPresent()) {
            changed = continuation
                    .get()
                    .requeue(
                            owned,
                            leaseId,
                            new ReviewJobRepository.CompositionRequeue(attempt, properties.maxRetries(), availableAt));
        }
        if (changed > 0) {
            bill(owned, counts);
            return Outcome.REQUEUED;
        }
        if (!admitted && owned.spentAttempts() < properties.maxRetries()) return Outcome.NONE;
        if (admitted) {
            changed = jobs.completeAdmittedOwnedBy(jobId, leaseId, attempt, now);
        } else if (cancellation != null) {
            changed = jobs.transitionToCancelledOwnedBy(
                    jobId, now, "The worker stopped this attempt.", cancellation, leaseId, attempt);
        } else {
            changed = jobs.transitionStatusOwnedBy(
                    jobId,
                    ReviewJobStatus.FAILED,
                    now,
                    "The worker that owned this job was lost. The retry limit is reached.",
                    leaseId,
                    attempt);
        }
        if (changed == 0) return Outcome.NONE;
        bill(owned, counts);
        if (admitted) return Outcome.COMPLETED;
        return cancellation != null ? Outcome.CANCELLED : Outcome.FAILED;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean retryUnadmitted(ReviewJob job, UUID leaseId, boolean bill) {
        ReviewJob locked = jobs.lockById(job.getId()).orElse(null);
        if (locked == null || !owns(locked, leaseId, job.getAttempt()) || ResultAdmissionService.isAdmitted(locked))
            return false;
        ReviewJobLlmUsage counts = bill ? counts(locked) : null;
        if (bill) hold(locked, counts);
        int changed = jobs.requeueOrphan(
                job.getId(),
                leaseId,
                properties.maxRetries(),
                Instant.now().plus(ReviewJobBackoff.compute(locked.spentAttempts() + 1)));
        if (changed > 0 && bill) bill(locked, counts);
        return changed > 0;
    }

    /** The error of a review that workers' drains stopped more often than {@link ReviewJob#MAX_DRAINS}. */
    static final String DRAINED_TOO_OFTEN = "Workers stopped this review for updates too often. It is not tried again.";

    private static boolean drained(@Nullable ReviewJobCancellationReason cancellation) {
        return cancellation == ReviewJobCancellationReason.DRAIN_GRACEFUL
                || cancellation == ReviewJobCancellationReason.DRAIN_IMMEDIATE;
    }

    private static boolean owns(@Nullable ReviewJob job, UUID leaseId, int attempt) {
        return job != null
                && job.getStatus() == ReviewJobStatus.RUNNING
                && leaseId.equals(job.getLeaseId())
                && job.getAttempt() == attempt;
    }

    private @Nullable ReviewJobLlmUsage counts(ReviewJob job) {
        return job.getExecutionStartedAt() != null
                ? jobs.findLlmUsageById(job.getId()).orElse(null)
                : null;
    }

    /**
     * Keeps the attempt's measurement before the job is handed on: the requeue zeroes the counters and the next claim
     * replaces the configuration, so only the locked attempt may start it. Called before every transition here.
     */
    private void hold(ReviewJob job, @Nullable ReviewJobLlmUsage counts) {
        if (job.getExecutionStartedAt() == null) return;
        usage.holdAttempt(job, admittedCharges(job), counts);
    }

    // The attempt's own measurement decides; the counters only start one that no proxied call registered.
    private void bill(ReviewJob job, @Nullable ReviewJobLlmUsage counts) {
        if (job.getExecutionStartedAt() == null) return;
        usage.settleAttempt(job, admittedCharges(job), null, counts);
    }

    private Map<ModelKind, FrozenCharge> admittedCharges(ReviewJob job) {
        ConfigSnapshot snapshot = frozen(job);
        LlmPriceSnapshot price = snapshot != null && snapshot.model().priceSnapshot() != null
                ? snapshot.model().priceSnapshot()
                : LlmPriceSnapshot.unpricedInstance();
        return FrozenCharge.admitted(snapshot, price);
    }

    private @Nullable ConfigSnapshot frozen(ReviewJob job) {
        try {
            return ConfigSnapshot.fromJson(job.getConfigSnapshot(), mapper);
        } catch (RuntimeException failure) {
            log.warn("Cannot read frozen model for recovered job {}", job.getId(), failure);
            return null;
        }
    }
}
