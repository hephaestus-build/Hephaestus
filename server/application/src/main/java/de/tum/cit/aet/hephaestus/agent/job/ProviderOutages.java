package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.proxy.ProviderOutage;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Review attempts that a model provider outage stopped. An outage is not the review's fault, so such an attempt goes
 * back to the queue with the backoff of its number and spends no retry. A measurement waits at most the outage window
 * from the review's submission. A composition keeps its run and the run's creation deadline, which bounds it. Past
 * its bound, the review ends as provider-unavailable and makes no claim about the work. Each requeue counts under its
 * cause, so a sustained outage stays visible.
 */
@Component
@ConditionalOnServerRole
public class ProviderOutages {

    private static final Logger log = LoggerFactory.getLogger(ProviderOutages.class);

    private final ReviewRunKind reviewRuns;
    private final LlmUsageRecorder usage;
    private final AgentCapacityProperties capacity;
    private final Map<ProviderOutage, Counter> requeued = new EnumMap<>(ProviderOutage.class);

    ProviderOutages(
            ReviewRunKind reviewRuns, LlmUsageRecorder usage, AgentCapacityProperties capacity, MeterRegistry meters) {
        this.reviewRuns = reviewRuns;
        this.usage = usage;
        this.capacity = capacity;
        for (ProviderOutage outage : ProviderOutage.values())
            requeued.put(outage, ReviewAttemptResults.infraRetryCounter(meters, outage.tag()));
    }

    /**
     * Requeues a measuring attempt that a provider outage stopped.
     *
     * @return false when the review is past its outage window, so the caller ends it as provider-unavailable; true when
     *     the attempt was requeued or no longer held its job
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean awaitMeasurement(ReviewJob job, AgentRunFence claim, ProviderOutage outage) {
        if (!withinWindow(job)) return false;
        requeue(job.getId(), claim, outage, backoff(claim));
        return true;
    }

    /**
     * Hands on a composition that left a destination open for a passing cause. Only a hand-on for a provider failure
     * whose newest failed call shows an outage continues free, on a fresh run with a fresh work deadline; a deadline,
     * routing or capacity hand-on, or an answered failure, spends a retry ({@link ReviewRunKind#retryComposition}). The
     * outage window from the review's submission bounds the free hand-ons, as it bounds a measurement: past it, the
     * review ends now as provider-unavailable.
     *
     * @param providerFailure whether the server closed the attempt for a provider failure
     * @return whether the attempt still held its job
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean handOnComposition(ReviewJob job, AgentRunFence claim, boolean providerFailure) {
        UUID jobId = job.getId();
        var outage = providerFailure ? outageOf(claim) : null;
        if (outage == null) return reviewRuns.retryComposition(jobId, claim, AgentRuns.Charge.SPEND, null);
        if (!withinWindow(job)) return reviewRuns.endProviderUnavailable(jobId, claim);
        Duration wait = compositionBackoff(job, claim);
        if (!reviewRuns.retryComposition(jobId, claim, AgentRuns.Charge.FREE, wait)) return false;
        counted(jobId, claim, outage, wait);
        return true;
    }

    /**
     * The outage that the claim's newest failed model call shows; null when none failed or the call was answered. A
     * sandbox reports "no reply" also for calls that the provider answered with an error, so only the proxy's record
     * of the call tells an outage apart.
     */
    public @Nullable ProviderOutage outageOf(AgentRunFence claim) {
        var failure = usage.latestFailure(claim.workspaceId(), claim.runId(), claim.epoch());
        return failure == null ? null : ProviderOutage.of(failure);
    }

    /** The retry backoff of the attempt's number: no retry is spent, so the spent count would not grow it. */
    private static Duration backoff(AgentRunFence claim) {
        return AgentRuns.backoff(ReviewRunKind.attempt(claim.epoch()) + 1);
    }

    /**
     * The backoff of the composition's own attempt number, counted from the measuring attempt that admitted its
     * results: the measurement's attempts must not use up the composition's short window.
     */
    private static Duration compositionBackoff(ReviewJob job, AgentRunFence claim) {
        Integer admitted = job.getAdmissionAttempt();
        int own = ReviewRunKind.attempt(claim.epoch()) - (admitted == null ? 0 : admitted);
        return AgentRuns.backoff(Math.max(1, own));
    }

    /** Whether the review is still inside its outage window, counted from its submission. */
    private boolean withinWindow(ReviewJob job) {
        return Instant.now().isBefore(job.getSubmittedAt().plus(capacity.modelOutageWindow()));
    }

    private boolean requeue(UUID jobId, AgentRunFence claim, ProviderOutage outage, Duration wait) {
        if (!reviewRuns.requeue(jobId, claim, AgentRuns.Charge.FREE, wait, null)) return false;
        counted(jobId, claim, outage, wait);
        return true;
    }

    /** Counts the free requeue once it commits, so a sustained outage stays visible. */
    private void counted(UUID jobId, AgentRunFence claim, ProviderOutage outage, Duration wait) {
        TransactionCallbacks.afterCommit(Objects.requireNonNull(requeued.get(outage))::increment);
        log.warn(
                "Requeuing job {} ({}): the model provider is unavailable, so attempt {} spends no retry and the next"
                        + " waits {}",
                jobId,
                outage.tag(),
                ReviewRunKind.attempt(claim.epoch()),
                wait);
    }
}
