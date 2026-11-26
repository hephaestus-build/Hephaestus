package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.gateway.WorkspaceBudgetExceededException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ReviewSourceNotReadyException;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.proxy.ProviderOutage;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.metrics.ReviewRefusalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What a review attempt's measurement comes to, once its sandbox sent its result: the attempt ends with its outcome,
 * is handed on to the run that composes its admitted results, or goes back to the queue for another attempt. Each
 * write is fenced on the attempt's run, so a claim that lost its run writes nothing.
 */
@Component
@ConditionalOnAgentRuntime
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
public class ReviewAttemptResults {

    private static final Logger log = LoggerFactory.getLogger(ReviewAttemptResults.class);

    private final ReviewJobRepository jobRepository;
    private final ResultAdmissionService admission;
    private final PracticePiAdapter practiceAgent;
    private final ReviewAttemptPreparation preparation;
    private final ReviewJobTerminal terminal;
    private final ReviewRunKind reviewRuns;
    private final ProviderOutages outages;
    private final ReviewAttemptOutputs outputs;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ReviewJobTelemetry jobTelemetry;
    private final Map<RequeueCause, Counter> infraRetryRequeued = new EnumMap<>(RequeueCause.class);
    private final Counter serverErrors;

    public ReviewAttemptResults(
            ReviewJobRepository jobRepository,
            ResultAdmissionService admission,
            PracticePiAdapter practiceAgent,
            ReviewAttemptPreparation preparation,
            ReviewJobTerminal terminal,
            ReviewRunKind reviewRuns,
            ProviderOutages outages,
            ReviewAttemptOutputs outputs,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            ReviewJobTelemetry jobTelemetry) {
        this.jobRepository = jobRepository;
        this.admission = admission;
        this.practiceAgent = practiceAgent;
        this.preparation = preparation;
        this.terminal = terminal;
        this.reviewRuns = reviewRuns;
        this.outages = outages;
        this.outputs = outputs;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.jobTelemetry = jobTelemetry;
        serverErrors = Counter.builder(AgentMetrics.AGENT_JOB_SERVER_ERROR)
                .description("Attempts that ended because this server answered their results with its own error")
                .register(meterRegistry);
        for (RequeueCause cause : RequeueCause.values())
            infraRetryRequeued.put(cause, infraRetryCounter(meterRegistry, cause.tag()));
    }

    /** The count of attempts requeued for {@code cause}: a requeue cause, or a provider outage. */
    static Counter infraRetryCounter(MeterRegistry meterRegistry, String cause) {
        return Counter.builder(AgentMetrics.AGENT_JOB_INFRA_RETRY_REQUEUED)
                .description("Jobs requeued (not failed) after a retryable preparation or infrastructure failure, or a"
                        + " provider outage, by cause")
                .tag("cause", cause)
                .register(meterRegistry);
    }

    @Nullable
    ReviewJobStatus measured(UUID jobId, ReviewJob job, AgentRunFence fence, int endedBefore, SandboxResult result) {
        Instant receivedAt = Instant.now();
        ReviewJob retained;
        try {
            retained = preparation.owned(jobId, fence);
        } catch (ResultAdmissionService.StaleAttemptException lost) {
            return null;
        }
        Instant startedAt = retained.getExecutionStartedAt();
        if (startedAt != null && !receivedAt.isBefore(startedAt)) {
            jobTelemetry.record(ReviewJobTelemetry.Phase.EXECUTION, Duration.between(startedAt, receivedAt));
        }
        AgentResult agentResult = practiceAgent.parseResult(result);
        // A server error is a defect here, not an outage, and another attempt would meet it again. The attempt keeps
        // what it staged and ends the rest, with no retry.
        boolean serverError = result.exitCode() == SandboxLayout.EXIT_SERVER_ERROR;
        if (serverError) {
            serverErrors.increment();
            log.error("The server refused a review's results with its own error, so the attempt ends: jobId={}", jobId);
        }
        // The sandbox could not prepare the workspace for a cause that every attempt meets: a capture failure. The
        // attempt keeps what it staged and ends the rest as a preparation failure, with no retry.
        boolean unprepared = result.exitCode() == SandboxLayout.EXIT_WORKSPACE_UNPREPARED;
        // The provider answered none of the run's calls, and the proxy's record of them shows an outage, not an
        // answered
        // error. The outage is not the review's: it keeps what it staged and spends no retry until its outage window
        // ends, and then makes no claim about the work.
        ProviderOutage outage = result.exitCode() == SandboxLayout.EXIT_PROVIDER_UNREACHABLE
                ? transactionTemplate.execute(status -> outages.outageOf(fence))
                : null;
        boolean providerUnreachable = outage != null;
        if (!ResultAdmissionService.isAdmitted(retained)
                && admission.hasStaged(retained)
                && !admission.sealIfComplete(new ResultAdmissionService.AdmissionIdentity(
                        retained.getId(), retained.getWorkspaceId(), retained.getAttempt(), retained.getRunId()))) {
            if (outage != null) {
                if (awaitProvider(retained, fence, outage)) return null;
            } else if (!serverError && !unprepared) {
                // A slow provider can take many attempts. Each one that moved the review on keeps the retries for
                // the attempts that move nothing.
                if (admission.endedPractices(retained) > endedBefore && continueProgressed(retained, fence))
                    return null;
                if (requeueForAnotherAttempt(retained, fence, RequeueCause.SANDBOX)) return null;
            }
            admission.finishInterrupted(
                    new ResultAdmissionService.AdmissionIdentity(
                            retained.getId(), retained.getWorkspaceId(), retained.getAttempt(), retained.getRunId()),
                    serverError
                            ? ReviewStopReason.EXECUTION_FAILED
                            : unprepared
                                    ? ReviewStopReason.PREPARATION_FAILED
                                    : providerUnreachable
                                            ? ReviewStopReason.PROVIDER_UNAVAILABLE
                                            : result.timedOut()
                                                    ? ReviewStopReason.DEADLINE
                                                    : ReviewStopReason.EXECUTION_INCOMPLETE);
        }

        if (outage != null) {
            ReviewJob unanswered;
            try {
                unanswered = preparation.owned(jobId, fence);
            } catch (ResultAdmissionService.StaleAttemptException lost) {
                return null;
            }
            if (!ResultAdmissionService.isAdmitted(unanswered)) {
                if (!awaitProvider(unanswered, fence, outage)) endProviderUnavailable(unanswered, fence);
                return null;
            }
        } else {
            // The server was unreachable, or the provider answered every call with an error. A bounded retry keeps
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
        }
        // The sandbox has ended. A review that admitted results keeps what this attempt measured and is handed on to
        // the run that composes its feedback in the review's sandbox.
        ReviewJob measured;
        try {
            measured = preparation.owned(jobId, fence);
        } catch (ResultAdmissionService.StaleAttemptException lost) {
            return null;
        }
        if (ResultAdmissionService.isAdmitted(measured) && reviewRuns.composes(measured)) {
            boolean handedOn =
                    outputs.handOnToCompose(jobId, fence, new ReviewAttemptOutputs.Measured(agentResult, result));
            if (unprepared) workspaceUnprepared(job, handedOn ? "STAGED_KEPT" : "OWNERSHIP_LOST");
            return null;
        }

        ReviewJobStatus terminalStatus = outputs.persistTerminalState(jobId, fence, agentResult, result);
        if (terminalStatus != null) forgetCompositionInputs(job);
        if (unprepared) workspaceUnprepared(job, terminalStatus == null ? "OWNERSHIP_LOST" : terminalStatus.name());
        return terminalStatus;
    }

    /** The sandbox could not prepare the workspace for a cause that every attempt meets, as {@code result} ended it. */
    private void workspaceUnprepared(ReviewJob job, String result) {
        jobTelemetry.preparationFailed(
                job,
                PreparationFailure.of(
                        PreparationFailure.Stage.WORKSPACE,
                        new JobPreparationException(
                                JobPreparationException.Reason.WORKSPACE_RENDERING_FAILED,
                                "The sandbox could not prepare the attempt's workspace")),
                result);
    }

    /**
     * Hands on an attempt that measured nothing, whose seal took results that an earlier attempt staged, to the run
     * that composes their feedback, as {@link #measured} does for an admitted attempt. The caller asks only after such
     * a seal: an attempt whose seal admitted only its own results, such as Does not apply, completes directly.
     *
     * @return false when the seal admitted no result, or the attempt no longer holds its job
     */
    boolean handOnUnmeasured(UUID jobId, AgentRunFence fence) {
        ReviewJob sealed;
        try {
            sealed = preparation.owned(jobId, fence);
        } catch (ResultAdmissionService.StaleAttemptException lost) {
            return false;
        }
        return admission.admittedResults(sealed)
                && reviewRuns.composes(sealed)
                && outputs.handOnToCompose(jobId, fence, null);
    }

    /**
     * Completes an attempt that had nothing left to measure, without its sandbox. When its seal admitted results that
     * an earlier attempt staged, their feedback is composed first ({@link #handOnUnmeasured}).
     *
     * @param sealedStaged whether the attempt's seal admitted a result that an earlier attempt staged
     */
    void endUnmeasured(UUID jobId, AgentRunFence fence, boolean sealedStaged) {
        if (!sealedStaged || !handOnUnmeasured(jobId, fence))
            transactionTemplate.executeWithoutResult(status -> {
                if (terminal.transition(jobId, fence, ReviewJobStatus.COMPLETED, Instant.now(), null) > 0)
                    jobRepository.findById(jobId).ifPresent(completed -> {
                        completed.setProcessingStatus(ProcessingStatus.PENDING);
                        jobRepository.saveAndFlush(completed);
                    });
            });
    }

    /** Its feedback is decided: no attempt reads the retained inputs again. The retention sweep removes what this leaves. */
    private void forgetCompositionInputs(ReviewJob job) {
        terminal.forgetCompositionInputs(job);
    }

    private static @Nullable RequeueCause unreachableReason(int exitCode) {
        if (exitCode == SandboxLayout.EXIT_SERVER_UNREACHABLE) return RequeueCause.SERVER_UNREACHABLE;
        if (exitCode == SandboxLayout.EXIT_PROVIDER_UNREACHABLE) return RequeueCause.PROVIDER_UNREACHABLE;
        return null;
    }

    /** @return false past the review's outage window; true when the attempt was requeued or lost its job */
    private boolean awaitProvider(ReviewJob job, AgentRunFence fence, ProviderOutage outage) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> outages.awaitMeasurement(job, fence, outage)));
    }

    /** Ends an attempt whose provider stayed unavailable past the outage window. Settlement reads its outcome. */
    private void endProviderUnavailable(ReviewJob job, AgentRunFence fence) {
        JsonNode output = objectMapper.createObjectNode().put("outcome", RefusalOutcome.PROVIDER_UNAVAILABLE.name());
        Integer ended = transactionTemplate.execute(status -> terminal.refuse(job.getId(), fence, output));
        if (ended != null && ended > 0)
            log.warn("The model provider stayed unavailable past the review's outage window: jobId={}", job.getId());
    }

    /**
     * The attempt failed before its sandbox measured anything. An over-budget workspace ends it refused. A failure that
     * another attempt can outlast requeues it and spends a retry. Any other ends it failed, with its preparation's
     * bounded diagnosis.
     *
     * @param preparation the failed preparation; null when the failure came after the attempt's execution started
     */
    void unprepared(
            ReviewJob job, AgentRunFence fence, @Nullable PreparationFailure preparation, RuntimeException failure) {
        switch (failure) {
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
        boolean retryable = preparation != null ? isRetryablePreparation(failure) : isRetryable(failure);
        if (retryable
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
        // A lock timeout aborts its statement before anything commits, so no accounting can replay.
        return failure instanceof PessimisticLockingFailureException
                || failure instanceof ReviewSourceNotReadyException
                || failure instanceof SandboxInfrastructureException
                || failure instanceof IOException
                || failure instanceof UncheckedIOException
                || transientCapture(PreparationFailure.captureOf(failure));
    }

    /** Database failures before execution may requeue the preparation, without replaying terminal accounting. */
    static boolean isRetryablePreparation(Throwable failure) {
        return isRetryable(failure)
                || failure instanceof TransientDataAccessException
                || failure instanceof RecoverableDataAccessException
                || failure instanceof DataAccessResourceFailureException
                || failure instanceof CannotCreateTransactionException;
    }

    /**
     * A capture that failed, or whose source was not ready, can succeed on another attempt; the same item captured on
     * earlier attempts. Removed or hidden work, withheld consent, a lost version and an occasion that kept no record do
     * not come back.
     */
    private static boolean transientCapture(@Nullable ItemCaptureException capture) {
        return capture != null
                && switch (capture.code()) {
                    // A new attempt captures again on the server that runs it.
                    case CAPTURE_FAILED, SOURCE_NOT_READY, CHECKOUT_UNVERIFIED -> true;
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
        /** The provider answered every model call of the run with an error: a retry is spent, unlike an outage. */
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
}
