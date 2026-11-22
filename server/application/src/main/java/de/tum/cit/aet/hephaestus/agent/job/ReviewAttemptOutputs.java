package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.runtime.StorableText;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.Serial;
import java.time.Duration;
import java.time.Instant;
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
 * Keeps on its job what a review attempt's sandbox put out: its output, exit code and practice-script runs. An attempt
 * that ends writes them with its terminal state. An attempt whose admitted review is handed on to its composition
 * keeps them in the transaction of the hand-on. Each write is fenced on the attempt's run, and a transient database
 * failure retries it.
 */
@Component
@ConditionalOnAgentRuntime
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
public class ReviewAttemptOutputs {

    private static final Logger log = LoggerFactory.getLogger(ReviewAttemptOutputs.class);

    private final ReviewJobRepository jobRepository;
    private final ReviewJobTerminal terminal;
    private final ReviewRunKind reviewRuns;
    private final PracticeScriptRunRecorder practiceScriptRuns;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final LlmUsageEventRepository usageEvents;
    private final RetryTemplate terminalPersistRetries = retryTemplate(TERMINAL_PERSIST_POLICY);

    public ReviewAttemptOutputs(
            ReviewJobRepository jobRepository,
            ReviewJobTerminal terminal,
            ReviewRunKind reviewRuns,
            PracticeScriptRunRecorder practiceScriptRuns,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            LlmUsageEventRepository usageEvents) {
        this.jobRepository = jobRepository;
        this.terminal = terminal;
        this.reviewRuns = reviewRuns;
        this.practiceScriptRuns = practiceScriptRuns;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.usageEvents = usageEvents;
    }

    /**
     * What the server knows of an attempt beside the sandbox's own files.
     *
     * @param serverError how this server failed the attempt's results; null when it did not
     * @param nothingRan no practice was evaluated, and the server's usage ledger holds no model call of the attempt
     */
    record AttemptFacts(@Nullable String serverError, boolean nothingRan) {
        static final AttemptFacts NONE = new AttemptFacts(null, false);
    }

    /** What a measuring attempt that ended with {@code status} reports as its error; null for a completed one. */
    static @Nullable String terminalError(
            ReviewJobStatus status,
            SandboxResult sandboxResult,
            AgentResult agentResult,
            AttemptFacts facts,
            boolean storable) {
        return switch (status) {
            case TIMED_OUT -> "The container timed out.";
            case FAILED ->
                !storable ? UNSTORABLE_RESULT_MESSAGE : failure(sandboxResult.exitCode(), agentResult, facts);
            default -> null;
        };
    }

    private static String failure(int exitCode, AgentResult agentResult, AttemptFacts facts) {
        String failure;
        if (exitCode == 0) failure = "The runner did not return a valid result.";
        else if (exitCode == SandboxLayout.EXIT_WORKSPACE_UNPREPARED)
            failure = "The sandbox could not prepare the attempt's workspace.";
        else if (exitCode == SandboxLayout.EXIT_SERVER_ERROR)
            failure = Objects.requireNonNullElse(
                    facts.serverError(), "The server answered the review's results with its own error.");
        else {
            String why = RunnerEnd.describe(exitCode, agentResult.output());
            failure = "The container exited with code " + exitCode + "." + (why == null ? "" : " " + why);
        }
        return facts.nothingRan() ? failure + " No practice was evaluated, and no model was called." : failure;
    }

    /** Read only for a failed attempt: a completed one reports no error. */
    static AttemptFacts factsOf(ReviewJob job, AgentResult agentResult, LlmUsageEventRepository usageEvents) {
        // The count of evaluated practices is the sandbox's word; a call that the server's ledger holds is not.
        boolean noneEvaluated = Integer.valueOf(0).equals(agentResult.output().get(PiResultParser.PRACTICES_EVALUATED));
        boolean noCall = noneEvaluated
                && usageEvents
                                .countReviewCalls(job.getWorkspaceId(), job.getId(), job.getAttempt())
                                .getCalls()
                        == 0;
        return new AttemptFacts(AttemptServerErrors.describe(job.getCapture(), job.getAttempt()), noCall);
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

    /** What a measuring attempt's sandbox reported, which the attempt keeps when it hands its review on. */
    record Measured(AgentResult agentResult, SandboxResult sandboxResult) {}

    /**
     * Hands the admitted review of the attempt that runs here on to the run that composes its feedback in the review's
     * sandbox ({@link ReviewRunKind#continueToCompose}). An attempt that measured keeps its output, exit code and
     * practice-script runs first, in the same transaction, because no later attempt reports them again.
     *
     * @return false when the attempt no longer holds its job
     */
    boolean handOnToCompose(UUID jobId, AgentRunFence fence, @Nullable Measured measured) {
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
                            .put(
                                    "error",
                                    terminalError(
                                            status,
                                            measured.sandboxResult(),
                                            measured.agentResult(),
                                            status == ReviewJobStatus.FAILED
                                                    ? factsOf(locked, measured.agentResult(), usageEvents)
                                                    : AttemptFacts.NONE,
                                            storable)));
        locked.setOutput(kept);
        locked.setExitCode(measured.sandboxResult().exitCode());
        jobRepository.saveAndFlush(locked);
        practiceScriptRuns.record(
                locked,
                measured.sandboxResult().outputFiles().get(SandboxLayout.PRACTICE_SCRIPT_REPORT_FILE),
                Instant.now());
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
    @Nullable
    ReviewJobStatus persistTerminalState(
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
            String errorMessage = terminalError(
                    terminalStatus,
                    sandboxResult,
                    agentResult,
                    terminalStatus == ReviewJobStatus.FAILED
                            ? factsOf(locked, agentResult, usageEvents)
                            : AttemptFacts.NONE,
                    storable);
            Instant finishedAt = Instant.now();
            int updated = terminal.transition(jobId, fence, terminalStatus, finishedAt, errorMessage);
            if (updated == 0) {
                log.info("Job no longer owned/RUNNING, skipping output persist: jobId={}", jobId);
                return null;
            }

            ReviewJob freshJob = jobRepository.findById(jobId).orElseThrow();

            // Admitted claims require their complete supporting text.
            freshJob.setOutput(
                    storable ? output : objectMapper.createObjectNode().put("resultNotStored", UNSTORABLE_TEXT));
            freshJob.setExitCode(sandboxResult.exitCode());

            if (terminalStatus == ReviewJobStatus.COMPLETED) {
                freshJob.setProcessingStatus(ProcessingStatus.PENDING);
            }

            jobRepository.saveAndFlush(freshJob);

            practiceScriptRuns.record(
                    freshJob, sandboxResult.outputFiles().get(SandboxLayout.PRACTICE_SCRIPT_REPORT_FILE), finishedAt);
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
