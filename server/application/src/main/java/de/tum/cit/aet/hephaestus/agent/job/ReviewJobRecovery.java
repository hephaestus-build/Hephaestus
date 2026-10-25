package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Recovers lost attempts without repeating admitted results or losing their measured spend. */
@Component
@ConditionalOnCaptureRole
@WorkspaceAgnostic("Recovery uses the supplied job id, owning worker, and attempt fence")
public class ReviewJobRecovery {
    enum Outcome {
        NONE,
        REQUEUED,
        FAILED,
        CANCELLED
    }

    private static final Logger log = LoggerFactory.getLogger(ReviewJobRecovery.class);
    private static final Set<String> STOPPED_BY_THE_RUN = Set.of("SAFETY", "STALL");
    private final ReviewJobRepository jobs;
    private final AgentProperties properties;
    private final ObjectMapper mapper;
    private final ReviewAttemptUsageService usage;
    private final Optional<ReviewCompositionContinuation> continuation;
    private final Optional<PracticeScriptRunRecorder> scriptRuns;

    public ReviewJobRecovery(
            ReviewJobRepository jobs,
            AgentProperties properties,
            ObjectMapper mapper,
            ReviewAttemptUsageService usage,
            Optional<ReviewCompositionContinuation> continuation,
            Optional<PracticeScriptRunRecorder> scriptRuns) {
        this.jobs = jobs;
        this.properties = properties;
        this.mapper = mapper;
        this.usage = usage;
        this.continuation = continuation;
        this.scriptRuns = scriptRuns;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome recover(
            UUID jobId, String workerId, int attempt, @Nullable ReviewJobCancellationReason cancellation) {
        continuation.ifPresent(ReviewCompositionContinuation::holdRecoveryCustody);
        ReviewJob job = jobs.lockById(jobId).orElse(null);
        if (!owns(job, workerId, attempt)) return Outcome.NONE;
        ReviewJob owned = Objects.requireNonNull(job);
        ReviewJobLlmUsage counts = counts(owned);
        hold(owned, counts);
        Instant now = Instant.now();
        Instant availableAt = now.plus(ReviewJobBackoff.compute(attempt + 1));
        int changed = attempt < properties.maxRetries()
                ? jobs.requeueOrphan(jobId, workerId, properties.maxRetries(), availableAt)
                : 0;
        boolean admitted = ResultAdmissionService.isAdmitted(owned);
        if (changed == 0 && admitted && continuation.isPresent()) {
            changed = continuation
                    .get()
                    .requeue(
                            owned,
                            workerId,
                            new ReviewJobRepository.CompositionRequeue(
                                    attempt, properties.maxRetries(), now, availableAt));
        }
        if (changed > 0) {
            bill(owned, null, counts);
            return Outcome.REQUEUED;
        }
        if (!admitted && attempt < properties.maxRetries()) return Outcome.NONE;
        if (admitted) {
            changed = jobs.failAdmittedOwnedBy(
                    jobId, workerId, attempt, now, ResultAdmissionService.INTERRUPTED_AFTER_ADMISSION);
        } else if (cancellation != null) {
            changed = jobs.transitionToCancelledOwnedBy(
                    jobId, now, "The worker stopped this attempt.", cancellation, workerId, attempt);
        } else {
            changed = jobs.transitionStatusOwnedBy(
                    jobId,
                    ReviewJobStatus.FAILED,
                    now,
                    "The worker that owned this job was lost. The retry limit is reached.",
                    workerId,
                    attempt);
        }
        if (changed == 0) return Outcome.NONE;
        bill(owned, null, counts);
        return !admitted && cancellation != null ? Outcome.CANCELLED : Outcome.FAILED;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean retryUnadmitted(ReviewJob job, String workerId, boolean bill) {
        ReviewJob locked = jobs.lockById(job.getId()).orElse(null);
        if (locked == null || !owns(locked, workerId, job.getAttempt()) || ResultAdmissionService.isAdmitted(locked))
            return false;
        ReviewJobLlmUsage counts = bill ? counts(locked) : null;
        if (bill) hold(locked, counts);
        int changed = jobs.requeueOrphan(
                job.getId(),
                workerId,
                properties.maxRetries(),
                Instant.now().plus(ReviewJobBackoff.compute(job.getAttempt() + 1)));
        if (changed > 0 && bill) bill(locked, null, counts);
        return changed > 0;
    }

    /**
     * Continues an admitted attempt whose feedback composition the run stopped. The locked row decides. After an
     * accepted review on the work, only private feedback is owed, and only while the review's prepared private pieces
     * leave a NOT_MET practice undecided; the attempt's own report does not matter, as a response the runner lost does
     * not undo the review the row accepted. Without one, only the attempt that admitted the results may continue, and
     * only when its feedback report says the run stopped the review on the work itself. An attempt that continued it
     * had the whole run for composition and ends with its outcome. A prepared private piece stays as it is, and the
     * next attempt composes only for the practices that no prepared piece decides. Recovery after a lost worker is
     * separate and keeps its own shared retry limit. The attempt returned, so it is billed once from its own report,
     * selected as at a terminal write, and keeps the practice script runs it reported.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean continueComposition(
            ReviewJob job,
            String workerId,
            AgentResult.@Nullable LlmUsage runnerUsage,
            byte @Nullable [] scriptReport,
            byte @Nullable [] feedbackReport) {
        if (continuation.isEmpty()) return false;
        continuation.get().holdRecoveryCustody();
        ReviewJob locked = jobs.lockById(job.getId()).orElse(null);
        if (!owns(locked, workerId, job.getAttempt())) return false;
        ReviewJob owned = Objects.requireNonNull(locked);
        if (!ResultAdmissionService.isAdmitted(owned)) return false;
        boolean owed = owned.getPublicReviewDigest() != null
                ? continuation.get().privateFeedbackOwed(owned)
                : !ResultAdmissionService.isContinuation(owned) && reviewOnTheWorkStopped(owned, feedbackReport);
        if (!owed) return false;
        // Read before the requeue zeroes them; they only start a measurement no proxied call registered.
        ReviewJobLlmUsage counts = counts(owned);
        hold(owned, counts);
        Instant now = Instant.now();
        int changed = continuation
                .get()
                .requeue(
                        owned,
                        workerId,
                        new ReviewJobRepository.CompositionRequeue(
                                owned.getAttempt(),
                                properties.maxRetries(),
                                now,
                                now.plus(ReviewJobBackoff.compute(owned.getAttempt() + 1))));
        if (changed > 0) {
            bill(owned, runnerUsage, counts);
            // The row locked as attempt N keys and admits the runs; the requeue detached it unchanged. The attempt that
            // continues it measures nothing, so no later report replaces them.
            scriptRuns.ifPresent(runs -> {
                ConfigSnapshot snapshot = frozen(owned);
                runs.record(owned, scriptReport, snapshot == null ? Map.of() : snapshot.practiceScriptModels(), now);
            });
        }
        return changed > 0;
    }

    /**
     * Whether the attempt's feedback report, one JSON object as the runner writes it, is for the admission the row
     * holds and names exactly one failure of the review on the work, a stop the run made. Anything else asks for
     * nothing.
     */
    private boolean reviewOnTheWorkStopped(ReviewJob owned, byte @Nullable [] feedbackReport) {
        if (feedbackReport == null) return false;
        JsonNode report;
        try {
            report = mapper.readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(feedbackReport);
        } catch (JacksonException unreadable) {
            return false;
        }
        if (report == null || !report.isObject()) return false;
        JsonNode digest = report.get("admissionDigest");
        JsonNode failures = report.get("compositionFailures");
        if (digest == null
                || !digest.isString()
                || !digest.asString().equals(owned.getAdmissionDigest())
                || failures == null
                || !failures.isArray()) return false;
        int publicFailures = 0;
        boolean stopped = false;
        for (JsonNode failure : failures) {
            JsonNode phase = failure.get("phase");
            JsonNode reason = failure.get("reason");
            if (phase == null || !phase.isString() || reason == null || !reason.isString()) return false;
            if (phase.asString().equals("PUBLIC_REVIEW")) {
                publicFailures++;
                stopped = STOPPED_BY_THE_RUN.contains(reason.asString());
            }
        }
        return publicFailures == 1 && stopped;
    }

    private static boolean owns(@Nullable ReviewJob job, String workerId, int attempt) {
        return job != null
                && job.getStatus() == ReviewJobStatus.RUNNING
                && workerId.equals(job.getWorkerId())
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
    private void bill(ReviewJob job, AgentResult.@Nullable LlmUsage runnerUsage, @Nullable ReviewJobLlmUsage counts) {
        if (job.getExecutionStartedAt() == null) return;
        usage.settleAttempt(job, admittedCharges(job), runnerUsage, counts);
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
