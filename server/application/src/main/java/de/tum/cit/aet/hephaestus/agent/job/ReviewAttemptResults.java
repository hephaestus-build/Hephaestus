package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.context.InsufficientEvidenceException;
import de.tum.cit.aet.hephaestus.agent.gateway.WorkspaceBudgetExceededException;
import de.tum.cit.aet.hephaestus.agent.handler.DefaultReviewExecutionHandler;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ReviewSourceNotReadyException;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.runtime.StorableText;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.metrics.ReviewRefusalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a review attempt's measurement comes to, once its sandbox sent its result: the attempt ends with its outcome,
 * is handed on to the run that composes its admitted results, or goes back to the queue for another attempt. Each
 * write is fenced on the attempt's run, so a claim that lost its run writes nothing.
 */
@Component
@ConditionalOnWorkerRole
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
public class ReviewAttemptResults {

    private static final Logger log = LoggerFactory.getLogger(ReviewAttemptResults.class);

    private final ReviewJobRepository jobRepository;
    private final DefaultReviewExecutionHandler handler;
    private final PracticePiAdapter practiceAgent;
    private final ReviewAttemptPreparation preparation;
    private final ReviewJobTerminal terminal;
    private final ReviewRunKind reviewRuns;
    private final PracticeScriptRunRecorder practiceScriptRuns;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ReviewJobTelemetry jobTelemetry;
    private final RetryTemplate terminalPersistRetries = retryTemplate(TERMINAL_PERSIST_POLICY);
    private final Map<RequeueCause, Counter> infraRetryRequeued = new EnumMap<>(RequeueCause.class);
    private final Counter serverErrors;

    public ReviewAttemptResults(
            ReviewJobRepository jobRepository,
            DefaultReviewExecutionHandler handler,
            PracticePiAdapter practiceAgent,
            ReviewAttemptPreparation preparation,
            ReviewJobTerminal terminal,
            ReviewRunKind reviewRuns,
            PracticeScriptRunRecorder practiceScriptRuns,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            ReviewJobTelemetry jobTelemetry) {
        this.jobRepository = jobRepository;
        this.handler = handler;
        this.practiceAgent = practiceAgent;
        this.preparation = preparation;
        this.terminal = terminal;
        this.reviewRuns = reviewRuns;
        this.practiceScriptRuns = practiceScriptRuns;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.jobTelemetry = jobTelemetry;
        serverErrors = Counter.builder(AgentMetrics.AGENT_JOB_SERVER_ERROR)
                .description("Attempts that ended because this server answered their results with its own error")
                .register(meterRegistry);
        for (RequeueCause cause : RequeueCause.values()) {
            infraRetryRequeued.put(
                    cause,
                    Counter.builder(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED)
                            .description("Jobs requeued (not failed) after a retryable preparation or infrastructure"
                                    + " failure, by cause")
                            .tag("cause", cause.tag())
                            .register(meterRegistry));
        }
    }

    /** What a measuring attempt that ended with {@code status} reports as its error; null for a completed one. */
    private static @Nullable String terminalError(
            ReviewJobStatus status, SandboxResult sandboxResult, boolean storable) {
        return switch (status) {
            case TIMED_OUT -> "The container timed out.";
            case FAILED ->
                !storable
                        ? UNSTORABLE_RESULT_MESSAGE
                        : sandboxResult.exitCode() == 0
                                ? "The runner did not return a valid result."
                                : sandboxResult.exitCode() == SandboxLayout.EXIT_SERVER_ERROR
                                        ? "The server answered the review's results with its own error."
                                        : "The container exited with code " + sandboxResult.exitCode() + ".";
            default -> null;
        };
    }

    static final String UNSTORABLE_RESULT_MESSAGE =
            "The runner returned a result with text the database cannot store, so the result was not kept.";

    private static final String UNSTORABLE_TEXT = "The text contains a NUL character or an unpaired surrogate.";

    /**
     * When the terminal accounting write is worth re-attempting. NOT the same question as
     * the requeue of a failed preparation, which requeues the whole JOB — conflating the two would charge
     * twice for a job that already spent money. {@code includes} matches nested causes, which is what
     * makes these usable directly against JPA's and {@code TransactionTemplate}'s wrapping.
     */
    static final RetryPolicy TERMINAL_PERSIST_POLICY = RetryPolicy.builder()
            .includes(
                    TransientDataAccessException.class,
                    RecoverableDataAccessException.class,
                    DataAccessResourceFailureException.class,
                    CannotCreateTransactionException.class)
            .maxRetries(2)
            .delay(Duration.ofMillis(200))
            // Growing rather than fixed: a lock timeout or a failover outlasts an immediate retry.
            .multiplier(2)
            .build();

    private static RetryTemplate retryTemplate(RetryPolicy policy) {
        RetryTemplate template = new RetryTemplate(policy);
        template.setRetryListener(new RetryListener() {
            @Override
            public void onRetryableExecution(RetryPolicy inUse, Retryable<?> operation, RetryState state) {
                // Fires after every execution, successful ones included; isSuccessful() is false
                // exactly when the attempt just recorded threw.
                if (state.isSuccessful()) {
                    return;
                }
                Throwable failure = state.getLastException();
                log.warn(
                        "{} failed (attempt {}): {}",
                        operation.getName(),
                        state.getExceptions().size(),
                        failure == null ? "none" : failure.getClass().getName());
            }
        });
        return template;
    }

    private static <R> Retryable<R> named(String name, Supplier<@Nullable R> work) {
        return new Retryable<>() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public R execute() {
                return work.get();
            }
        };
    }

    @Nullable
    ReviewJobStatus measured(UUID jobId, ReviewJob job, AgentRunFence fence, int endedBefore, SandboxResult result) {
        AgentResult agentResult = practiceAgent.parseResult(result);

        ReviewJob retained;
        try {
            retained = preparation.owned(jobId, fence);
        } catch (ResultAdmissionService.StaleAttemptException lost) {
            return null;
        }
        // A server error is a defect here, not an outage, and another attempt would meet it again. The attempt keeps
        // what it staged and ends the rest, with no retry.
        boolean serverError = result.exitCode() == SandboxLayout.EXIT_SERVER_ERROR;
        if (serverError) {
            serverErrors.increment();
            log.error("The server refused a review's results with its own error, so the attempt ends: jobId={}", jobId);
        }
        if (!ResultAdmissionService.isAdmitted(retained)
                && handler.hasStaged(retained)
                && !handler.completeStaged(retained)) {
            if (!serverError) {
                // A slow provider can take many attempts. Each one that moved the review on keeps the retries for
                // the attempts that move nothing.
                if (handler.endedPractices(retained) > endedBefore && continueProgressed(retained, fence)) return null;
                if (requeueForAnotherAttempt(retained, fence, RequeueCause.SANDBOX)) return null;
            }
            handler.finishInterrupted(
                    retained,
                    serverError
                            ? ReviewStopReason.EXECUTION_FAILED
                            : result.timedOut() ? ReviewStopReason.DEADLINE : ReviewStopReason.EXECUTION_INCOMPLETE);
        }

        // An infrastructure or provider failure can leave the run without deliverable work. A bounded retry keeps
        // staged practices and makes no claim about the reviewed work. Past the cap, the attempt ends here.
        RequeueCause unreachable = unreachableReason(result.exitCode());
        if (unreachable != null && requeueForAnotherAttempt(job, fence, unreachable)) {
            log.warn(
                    "Requeuing job {} ({}): nothing it measured can be delivered from here (attempt {})",
                    jobId,
                    unreachable.tag(),
                    job.getAttempt() + 1);
            return null;
        }
        // The sandbox has ended. A review that admitted results keeps what this attempt measured and is handed on to
        // the run that composes its feedback in the review's sandbox.
        ReviewJob measured;
        try {
            measured = preparation.owned(jobId, fence);
        } catch (ResultAdmissionService.StaleAttemptException lost) {
            return null;
        }
        if (ResultAdmissionService.isAdmitted(measured)) {
            handOnToCompose(jobId, fence, new Measured(agentResult, result));
            return null;
        }

        ReviewJobStatus terminalStatus = persistTerminalState(jobId, fence, agentResult, result);
        if (terminalStatus != null) forgetCompositionInputs(job);
        return terminalStatus;
    }

    /** Its feedback is decided: no attempt reads the retained inputs again. The retention sweep removes what this leaves. */
    private void forgetCompositionInputs(ReviewJob job) {
        terminal.forgetCompositionInputs(job);
    }

    /** What a measuring attempt's sandbox reported, which the attempt keeps when it hands its review on. */
    private record Measured(AgentResult agentResult, SandboxResult sandboxResult) {}

    /**
     * Hands the admitted review of the attempt that runs here on to the run that composes its feedback in the review's
     * sandbox ({@link ReviewRunKind#continueToCompose}). An attempt that measured keeps its output, exit code and
     * practice-script runs first, in the same transaction, because no later attempt reports them again.
     *
     * @return false when the attempt no longer holds its job
     */
    private boolean handOnToCompose(UUID jobId, AgentRunFence fence, @Nullable Measured measured) {
        try {
            return Boolean.TRUE.equals(terminalPersistRetries.execute(named(
                    "Hand-on to composition for jobId=" + jobId,
                    () -> transactionTemplate.execute(status -> {
                        reviewRuns.lock(fence.run());
                        if (measured != null && !keepMeasurement(jobId, fence, measured)) return false;
                        return reviewRuns.continueToCompose(jobId, fence).isPresent();
                    }))));
        } catch (RetryException e) {
            throw new TerminalPersistenceException(e);
        }
    }

    /** Keeps a measuring attempt's report on its job and records its script runs. */
    private boolean keepMeasurement(UUID jobId, AgentRunFence fence, Measured measured) {
        ReviewJob locked = jobRepository.lockById(jobId).orElse(null);
        if (locked == null
                || locked.getStatus() != ReviewJobStatus.RUNNING
                || locked.getAttempt() != ReviewRunKind.attempt(fence.epoch())
                || !fence.runId().equals(locked.getRunId())) return false;
        JsonNode output = objectMapper.valueToTree(measured.agentResult().output());
        boolean storable = textRepresentableInJsonb(output);
        ConfigSnapshot snapshot = ConfigSnapshot.fromJson(locked.getConfigSnapshot(), objectMapper);
        ObjectNode kept = storable && output instanceof ObjectNode report
                ? report
                : objectMapper.createObjectNode().put("resultNotStored", UNSTORABLE_TEXT);
        ReviewJobStatus determined = determineTerminalStatus(measured.sandboxResult(), measured.agentResult(), locked);
        ReviewJobStatus status =
                !storable && determined == ReviewJobStatus.COMPLETED ? ReviewJobStatus.FAILED : determined;
        // The composition run ends the job with what this attempt measured, so a timed-out measurement is not complete.
        if (status != ReviewJobStatus.COMPLETED)
            kept.set(
                    ReviewJobTerminal.MEASURED,
                    objectMapper
                            .createObjectNode()
                            .put("status", status.name())
                            .put("error", terminalError(status, measured.sandboxResult(), storable)));
        locked.setOutput(kept);
        locked.setExitCode(measured.sandboxResult().exitCode());
        jobRepository.saveAndFlush(locked);
        practiceScriptRuns.record(
                locked,
                measured.sandboxResult().outputFiles().get(SandboxLayout.PRACTICE_SCRIPT_REPORT_FILE),
                snapshot.practiceScriptModels(),
                Instant.now());
        return true;
    }

    private static @Nullable RequeueCause unreachableReason(int exitCode) {
        if (exitCode == SandboxLayout.EXIT_SERVER_UNREACHABLE) {
            return RequeueCause.SERVER_UNREACHABLE;
        }
        if (exitCode == SandboxLayout.EXIT_PROVIDER_UNREACHABLE) {
            return RequeueCause.PROVIDER_UNREACHABLE;
        }
        if (exitCode == SandboxLayout.EXIT_SIDE_LOST) {
            return RequeueCause.SANDBOX;
        }
        return null;
    }

    /**
     * The attempt failed before its sandbox measured anything. Too little evidence or an over-budget workspace ends it
     * refused. A failure that another attempt can outlast requeues it and spends a retry. Any other ends it failed,
     * with its preparation's bounded diagnosis.
     *
     * @param preparation the failed preparation; null when the failure came after the attempt's execution started
     */
    void unprepared(
            ReviewJob job, AgentRunFence fence, @Nullable PreparationFailure preparation, RuntimeException failure) {
        switch (failure) {
            case InsufficientEvidenceException refused -> refuseForEvidence(job, fence, refused);
            case WorkspaceBudgetExceededException exceeded ->
                refuse(
                        job,
                        fence,
                        new RefusedExecution(
                                RefusalOutcome.WORKSPACE_REFUSED,
                                RefusalReason.WORKSPACE_BUDGET_EXCEEDED,
                                exceeded.getBody().getProperties()),
                        "workspace_budget_exceeded");
            default -> fail(job, fence, failure, preparation);
        }
    }

    private void refuseForEvidence(ReviewJob job, AgentRunFence fence, InsufficientEvidenceException refused) {
        // This evidence was not staged, so no attempt owns its cleanup.
        try (PreparedJobInputs inputs = refused.preparedInputs()) {
            preparation.persistPreparedEvidence(job.getId(), fence, job.getWorkspaceId(), job.getAttempt(), inputs);
        } catch (RuntimeException persistence) {
            fail(job, fence, persistence, PreparationFailure.of(PreparationFailure.Stage.PROVENANCE, persistence));
            return;
        }
        refuse(
                job,
                fence,
                new RefusedExecution(RefusalOutcome.INSUFFICIENT_EVIDENCE, null, null),
                "insufficient_evidence");
    }

    private void refuse(ReviewJob job, AgentRunFence fence, RefusedExecution refusal, String reason) {
        JsonNode output = objectMapper.valueToTree(refusal);
        Integer refused = transactionTemplate.execute(status -> terminal.refuse(job.getId(), fence, output));
        if (refused != null && refused > 0) ReviewRefusalRecorder.record(meterRegistry, "execution", reason);
    }

    private record RefusedExecution(
            RefusalOutcome outcome,
            @Nullable RefusalReason reasonCode,
            @Nullable Map<String, Object> details) {}

    private enum RefusalReason {
        WORKSPACE_BUDGET_EXCEEDED
    }

    private void fail(
            ReviewJob job, AgentRunFence fence, RuntimeException failure, @Nullable PreparationFailure preparation) {
        String message = preparation != null
                ? preparation.message()
                : failure instanceof UncheckedIOException
                        ? "A file or network operation failed during execution."
                        : "The job failed during execution.";
        if (preparation == null) {
            log.error(
                    "Agent job failed: jobId={}, error={}, errorType={}",
                    job.getId(),
                    message,
                    failure.getClass().getName(),
                    failure);
        } else {
            // Bounded facts only, as on the job row: an exception message can name a private path or repository.
            log.error(
                    "Review preparation failed: jobId={}, stage={}, kind={}, reason={}, captureCode={}, capturedAt={},"
                            + " exceptionTypes={}",
                    job.getId(),
                    preparation.stage().name(),
                    preparation.kind().name(),
                    preparation.reason().name(),
                    preparation.captureCode(),
                    preparation.capturedAt(),
                    preparation.exceptionTypes());
        }
        if (isRetryable(failure)
                && requeueForAnotherAttempt(
                        job, fence, preparation != null ? RequeueCause.PREPARATION : RequeueCause.SANDBOX)) {
            if (preparation != null) jobTelemetry.preparationFailed(job, preparation, "REQUEUED");
            return;
        }
        Integer failed = transactionTemplate.execute(status -> {
            int updated = terminal.transition(job.getId(), fence, ReviewJobStatus.FAILED, Instant.now(), message);
            if (updated > 0 && preparation != null)
                jobRepository.recordPreparationFailure(
                        job.getId(),
                        job.getWorkspaceId(),
                        job.getAttempt(),
                        fence.runId(),
                        preparation.diagnosis(objectMapper, job.getAttempt()));
            return updated;
        });
        if (preparation != null)
            jobTelemetry.preparationFailed(
                    job, preparation, failed != null && failed > 0 ? ReviewJobStatus.FAILED.name() : "OWNERSHIP_LOST");
    }

    /**
     * Whether another attempt can outlast the failure. A deterministic failure, or a defect, would spend every retry
     * on a failure that does not resolve itself.
     */
    static boolean isRetryable(Throwable failure) {
        return failure instanceof ReviewSourceNotReadyException
                || failure instanceof SandboxInfrastructureException
                || failure instanceof IOException
                || failure instanceof UncheckedIOException
                || transientCapture(PreparationFailure.captureOf(failure));
    }

    /**
     * A capture that failed, or whose source was not ready, can succeed on another attempt; the same item captured on
     * earlier attempts. Removed or hidden work, withheld consent, a lost version and an occasion that kept no record do
     * not come back.
     */
    private static boolean transientCapture(@Nullable ItemCaptureException capture) {
        return capture != null
                && switch (capture.code()) {
                    case CAPTURE_FAILED, SOURCE_NOT_READY -> true;
                    case ITEM_REMOVED,
                            ITEM_NOT_VISIBLE,
                            CONSENT_WITHHELD,
                            VERSION_UNAVAILABLE,
                            OCCASION_EVIDENCE_UNAVAILABLE -> false;
                };
    }

    /** Why an attempt runs again: each cause asks the operator to look somewhere else. */
    enum RequeueCause {
        /** Before the sandbox started: the reviewed source, its inputs or the database. */
        PREPARATION,
        /** The sandbox could not start, initialize or finish, or a file or network operation failed in it. */
        SANDBOX,
        /** The sandbox could not reach the server's gateway. */
        SERVER_UNREACHABLE,
        /** No model call of the run was answered. */
        PROVIDER_UNREACHABLE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Hands an unfinished attempt that moved the review on to the next attempt, without spending a retry. */
    private boolean continueProgressed(ReviewJob job, AgentRunFence fence) {
        if (ResultAdmissionService.isAdmitted(job)) return false;
        Boolean updated = transactionTemplate.execute(
                status -> reviewRuns.requeue(job.getId(), fence, AgentRuns.Charge.FREE, Duration.ZERO, null));
        return Boolean.TRUE.equals(updated);
    }

    // The requeue of the run settles the attempt's open calls in the same transaction.
    private boolean requeueForAnotherAttempt(ReviewJob job, AgentRunFence fence, RequeueCause cause) {
        if (!reviewRuns.mayRetry(job)) return false;
        Boolean updated = transactionTemplate.execute(
                status -> reviewRuns.requeue(job.getId(), fence, AgentRuns.Charge.SPEND, Duration.ZERO, null));
        if (!Boolean.TRUE.equals(updated)) return false;
        Objects.requireNonNull(infraRetryRequeued.get(cause)).increment();
        return true;
    }

    // A failed runner's draft can reach delivery only through results the server already admitted.
    ReviewJobStatus determineTerminalStatus(SandboxResult sandboxResult, AgentResult agentResult, ReviewJob job) {
        if (sandboxResult.timedOut()) {
            return ReviewJobStatus.TIMED_OUT;
        }
        if (sandboxResult.exitCode() == 0) {
            // A clean exit without a valid result is the runner's failure, not a completed review.
            return agentResult != null && agentResult.success() ? ReviewJobStatus.COMPLETED : ReviewJobStatus.FAILED;
        }
        // The runner exits 42 before any model call when it cannot use the staged task envelope. Operators need to see
        // this apart from agent crashes; the runner's own log line names which check failed.
        if (sandboxResult.exitCode() == SandboxLayout.EXIT_ENVELOPE_MISMATCH) {
            log.error(
                    "Pi runner could not use the staged task envelope (exit {}): jobId={}, attempt={}. It is missing or"
                            + " unreadable, has another schemaVersion than the image, or has an invalid field; the"
                            + " runner log names which.",
                    SandboxLayout.EXIT_ENVELOPE_MISMATCH,
                    job.getId(),
                    job.getAttempt());
            meterRegistry.counter(AgentMetrics.AGENT_PI_ENVELOPE_MISMATCH).increment();
            return ReviewJobStatus.FAILED;
        }
        if (agentResult != null && agentResult.output() != null) {
            Object rawOutput = agentResult.output().get("rawOutput");
            if (rawOutput instanceof String raw && !raw.isBlank() && ResultAdmissionService.isAdmitted(job)) {
                log.info(
                        "Agent exited with code {} after result admission — continuing delivery",
                        sandboxResult.exitCode());
                return ReviewJobStatus.COMPLETED;
            }
            if (rawOutput != null && !(rawOutput instanceof String)) {
                log.warn(
                        "Agent exited with code {} and rawOutput is present but not a String (type={})",
                        sandboxResult.exitCode(),
                        rawOutput.getClass().getSimpleName());
            }
        }
        return ReviewJobStatus.FAILED;
    }

    // Retain RUNNING state if the terminal write cannot commit; rerunning completed provider work would bill it twice.
    // Its lease expires, and the run reaper ends the attempt and settles its open calls.
    private @Nullable ReviewJobStatus persistTerminalState(
            UUID jobId, AgentRunFence fence, AgentResult agentResult, SandboxResult sandboxResult) {
        JsonNode output = objectMapper.valueToTree(agentResult.output());
        boolean storable = textRepresentableInJsonb(output);
        if (!storable) {
            log.warn("Runner result has text jsonb cannot represent; recording it as not stored: jobId={}", jobId);
        }
        try {
            return terminalPersistRetries.execute(named(
                    "Terminal persistence for jobId=" + jobId,
                    () -> persistTerminalStateOnce(jobId, fence, agentResult, sandboxResult, output, storable)));
        } catch (RetryException e) {
            throw new TerminalPersistenceException(e);
        }
    }

    private @Nullable ReviewJobStatus persistTerminalStateOnce(
            UUID jobId,
            AgentRunFence fence,
            AgentResult agentResult,
            SandboxResult sandboxResult,
            JsonNode output,
            boolean storable) {
        return transactionTemplate.execute(status -> {
            ReviewJob locked = jobRepository.lockById(jobId).orElse(null);
            if (locked == null) return null;
            ReviewJobStatus determined = determineTerminalStatus(sandboxResult, agentResult, locked);
            ReviewJobStatus terminalStatus =
                    !storable && determined == ReviewJobStatus.COMPLETED ? ReviewJobStatus.FAILED : determined;
            String errorMessage = terminalError(terminalStatus, sandboxResult, storable);
            Instant finishedAt = Instant.now();
            int updated = terminal.transition(jobId, fence, terminalStatus, finishedAt, errorMessage);
            if (updated == 0) {
                log.info("Job no longer owned/RUNNING, skipping output persist: jobId={}", jobId);
                return null;
            }

            ReviewJob freshJob = jobRepository.findById(jobId).orElseThrow();
            ConfigSnapshot snapshot = ConfigSnapshot.fromJson(freshJob.getConfigSnapshot(), objectMapper);

            // Admitted claims require their complete supporting text.
            freshJob.setOutput(
                    storable ? output : objectMapper.createObjectNode().put("resultNotStored", UNSTORABLE_TEXT));
            freshJob.setExitCode(sandboxResult.exitCode());

            if (terminalStatus == ReviewJobStatus.COMPLETED) {
                freshJob.setProcessingStatus(ProcessingStatus.PENDING);
            }

            jobRepository.saveAndFlush(freshJob);

            practiceScriptRuns.record(
                    freshJob,
                    sandboxResult.outputFiles().get(SandboxLayout.PRACTICE_SCRIPT_REPORT_FILE),
                    snapshot.practiceScriptModels(),
                    finishedAt);
            return terminalStatus;
        });
    }

    // jsonb rejects decoded NUL and malformed UTF-16 in values and keys; literal escape text remains valid.
    private static boolean textRepresentableInJsonb(JsonNode node) {
        if (node.isString()) return StorableText.isStorable(node.asString());
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                if (!StorableText.isStorable(property.getKey()) || !textRepresentableInJsonb(property.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (!textRepresentableInJsonb(element)) return false;
            }
        }
        return true;
    }

    private static final class TerminalPersistenceException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private TerminalPersistenceException(Throwable cause) {
            super("Could not durably persist terminal job result", cause);
        }
    }
}
