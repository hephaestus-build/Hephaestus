package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.context.AttemptCopyReceipts;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService.AdmissionIdentity;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAttempt;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunAuthority;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunState;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxEnvBlocklist;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxGitHardening;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityBoundary;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusedException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The server side of a review's measurement in its own sandbox: the {@code REVIEW} run kind. Its claim starts the job;
 * the first request of the run prepares the attempt and serves the runner that
 * measures it. The attempt's inputs and its gateway session live until its run ends.
 */
@Component
@ConditionalOnAgentRuntime
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
@Profile("!specs & !cds-training")
public class ReviewSandboxRuns implements SandboxRunKind {

    private static final Logger log = LoggerFactory.getLogger(ReviewSandboxRuns.class);

    private final ReviewJobClaimService claims;
    private final ReviewAttemptCustody custody;
    private final ReviewJobRepository jobs;
    private final AttemptCopyReceipts receipts;
    private final SandboxGatewaySessions sessions;
    private final ObjectMapper objectMapper;
    private final ReviewAttemptResults results;
    private final ReviewRunKind reviewRuns;
    private final AgentRuns runs;
    private final ReviewAttemptMeasurements measurements;
    private final AgentRunAuthority authority;

    public ReviewSandboxRuns(
            ReviewJobClaimService claims,
            ReviewAttemptCustody custody,
            ReviewJobRepository jobs,
            SandboxGatewaySessions sessions,
            ObjectMapper objectMapper,
            ReviewAttemptResults results,
            ReviewRunKind reviewRuns,
            AgentRuns runs,
            AttemptCopyReceipts receipts,
            ReviewAttemptMeasurements measurements,
            AgentRunAuthority authority) {
        this.claims = claims;
        this.custody = custody;
        this.jobs = jobs;
        this.sessions = sessions;
        this.objectMapper = objectMapper;
        this.results = results;
        this.reviewRuns = reviewRuns;
        this.runs = runs;
        this.receipts = receipts;
        this.measurements = measurements;
        this.authority = authority;
    }

    @Override
    public AgentRunKind kind() {
        return AgentRunKind.REVIEW;
    }

    @Override
    public AgentSandboxKind sandbox() {
        return AgentSandboxKind.REVIEW;
    }

    /** An admitted review measures nothing again: its claim hands it on to the run that composes it. */
    @Override
    public boolean claimed(SandboxRun run) {
        var started = claims.claimLeased(run.fence());
        if (started.isEmpty()
                || !ResultAdmissionService.isContinuation(started.get().job())) return started.isPresent();
        if (reviewRuns
                .continueToCompose(started.get().job().getId(), run.fence())
                .isEmpty()) throw new IllegalStateException("The claimed attempt lost its job under its lock");
        return false;
    }

    /** No model call before the attempt's execution started: its work deadline counts from there. */
    @Override
    public Optional<ProxyAttempt> attempt(SandboxRun run) {
        // Every request of the sandbox authenticates here, so it reads the job's attempt, never its capture.
        return jobs.findCallByRun(run.fence().runId(), run.fence().workspaceId())
                .filter(job ->
                        job.attempt() == ReviewRunKind.attempt(run.fence().epoch()))
                .flatMap(job -> Optional.ofNullable(job.executionStartedAt())
                        .map(started -> new ProxyAttempt(
                                ProxyAttempt.Caller.REVIEW,
                                job.id(),
                                job.workspaceId(),
                                run.fence().epoch(),
                                job.runId(),
                                ReviewAttemptPreparation.workDeadline(
                                        started, ConfigSnapshot.fromJson(job.configSnapshot(), objectMapper)))));
    }

    @Override
    public JsonNode context(SandboxRun run) {
        ReviewAttemptMeasurements.Measurement measurement = measured(run);
        var context = objectMapper.createObjectNode();
        context.set("command", objectMapper.valueToTree(measurement.spec().command()));
        context.set("env", objectMapper.valueToTree(runnerEnvironment(measurement.spec())));
        context.put("workDeadline", measurement.workDeadline().toEpochMilli());
        context.put(
                "uploadDeadline",
                measurement
                        .workDeadline()
                        .plus(SandboxLayout.RESULT_UPLOAD_GRACE)
                        .toEpochMilli());
        return context;
    }

    /** The attempt's local archive, held while the replica serves its workspace. */
    SandboxGatewaySessions.Session session(SandboxRun run) {
        return measured(run).session();
    }

    /**
     * The attempt's measurement. Preparing it can wait for the evidence folder and rechecks the run's authority there,
     * without ending the run under the folder's lease and the preparation lock: the run ends here, once both unwound.
     */
    private ReviewAttemptMeasurements.Measurement measured(SandboxRun run) {
        try {
            return measurements.get(run);
        } catch (AuthorityRefusedException refused) {
            authority.require(run.fence(), AuthorityBoundary.CONTEXT_READ);
            throw refused;
        }
    }

    SandboxGatewaySessions.ReceivedResult receive(SandboxRun run, InputStream input, String contentDigest)
            throws IOException {
        ReviewJob job = job(run).orElseThrow(() -> new StaleRunException(run.fence()));
        var logScope = new ReviewJobLogScope(job, run.fence());
        try {
            ReviewAttemptPreparation.endedBefore(job);
            JsonNode capture = Objects.requireNonNull(job.getCapture());
            String image = capture.path("image").asString(null);
            if (image == null) throw new ReviewAttemptPreparation.FreshEpochRequired();
            return sessions.receiveAttempt(job, image, input, contentDigest);
        } catch (ReviewAttemptPreparation.FreshEpochRequired incomplete) {
            throw measurements.restart(run, job, ReviewAttemptMeasurements.identity(run, job), incomplete);
        } finally {
            logScope.close();
        }
    }

    /**
     * The attempt's result arrived: the steps after a review's sandbox run on it now, outside any transaction, and
     * they end the attempt, hand it on to its composition, or requeue it. Its inputs and its session go after them.
     */
    void measured(SandboxRun run, SandboxResult result) {
        ReviewJob job = job(run).orElseThrow(() -> new StaleRunException(run.fence()));
        var logScope = new ReviewJobLogScope(job, run.fence());
        try {
            AdmissionIdentity attempt = ReviewAttemptMeasurements.identity(run, job);
            int baseline;
            try {
                baseline = ReviewAttemptPreparation.endedBefore(job);
            } catch (ReviewAttemptPreparation.FreshEpochRequired incomplete) {
                throw measurements.restart(run, job, attempt, incomplete);
            }
            try {
                results.measured(job.getId(), job, run.fence(), baseline, result);
            } catch (PessimisticLockingFailureException contention) {
                // The session closes below, so the attempt ends without this result and its run requeues it.
                log.warn(
                        "A review's result was lost to lock contention, so its attempt runs again: jobId={}",
                        job.getId());
                throw contention;
            } finally {
                custody.release(attempt);
            }
        } finally {
            logScope.close();
        }
    }

    /**
     * The review kind reports a crash of its review programs as the end's cause. The attempt keeps it, as no sandbox log
     * reaches the server, before the end hands the attempt on. The write shares the end's transaction: it is fenced on
     * the attempt's run, so it changes nothing that the end would not.
     */
    @Override
    public boolean end(SandboxRun run, Outcome outcome, @Nullable String cause) {
        Optional<ReviewJob> found = job(run);
        String failure = RunnerEnd.programFailure(cause);
        if (failure != null)
            found.ifPresent(job -> {
                var attempt = ReviewAttemptMeasurements.identity(run, job);
                // The order of every writer of a review and its job (ReviewRunKind#requeue): the copy fence, the
                // parent, then the job. The requeue below takes them again in this transaction.
                receipts.holdCopyAdmission();
                jobs.lockParentOfJob(job.getId());
                jobs.recordDiagnosis(
                        attempt.jobId(),
                        attempt.workspaceId(),
                        attempt.attempt(),
                        attempt.runId(),
                        RunnerEnd.PROGRAM_FAILURE_KEY,
                        objectMapper
                                .createObjectNode()
                                .put("attempt", attempt.attempt())
                                .put("spent", job.spentAttempts())
                                .put("failure", failure)
                                .toString());
            });
        return endAttempt(run, found);
    }

    /**
     * Its result already ended the attempt, or handed it on; a run that sent none hands its attempt on, and spends a
     * retry, as a lost sandbox does.
     */
    @Override
    public boolean end(SandboxRun run, Outcome outcome) {
        return endAttempt(run, job(run));
    }

    private boolean endAttempt(SandboxRun run, Optional<ReviewJob> job) {
        if (job.isEmpty()) return true;
        var logScope = new ReviewJobLogScope(job.get(), run.fence());
        try {
            AdmissionIdentity attempt = ReviewAttemptMeasurements.identity(run, job.get());
            boolean held =
                    reviewRuns.requeue(job.get().getId(), run.fence(), AgentRuns.Charge.SPEND, Duration.ZERO, null);
            custody.release(attempt);
            return held
                    || runs.find(new AgentRunRef(
                                    run.fence().runId(), run.fence().workspaceId()))
                            .filter(found -> found.getState() == AgentRunState.ENDED)
                            .isPresent();
        } finally {
            logScope.close();
        }
    }

    /** The job that the run measures, while the run's claim still holds it. */
    private Optional<ReviewJob> job(SandboxRun run) {
        return ReviewAttemptMeasurements.job(jobs, run);
    }

    /**
     * The runner's environment from its spec, without the gateway's routes and credential: the spec's variables less the
     * blocked ones, the trace, and Git's hardening.
     */
    private static Map<String, String> runnerEnvironment(SandboxSpec spec) {
        Map<String, String> env = new HashMap<>();

        // Enforced values must overwrite caller-supplied variables: SandboxEnvBlocklist already drops the
        // dangerous ones, and this ordering keeps a value added below winning even if it is not listed there.
        for (var entry : spec.environment().entrySet()) {
            if (SandboxEnvBlocklist.isBlocked(entry.getKey())) {
                log.warn("Blocked dangerous environment variable: {}", entry.getKey());
            } else {
                env.put(entry.getKey(), entry.getValue());
            }
        }
        addTraceEnvironment(env);

        // The agent can clone a repository at runtime and carry a hostile .git/config in with it.
        env.putAll(SandboxGitHardening.environment());

        return env;
    }

    private static void addTraceEnvironment(Map<String, String> env) {
        String traceId = MDC.get(StructuredLogKeys.TRACE_ID);
        String spanId = MDC.get(StructuredLogKeys.SPAN_ID);
        if (traceId != null && spanId != null) {
            env.put("TRACE_ID", traceId);
            env.put(
                    "TRACEPARENT",
                    "00-" + traceId + "-" + spanId + "-"
                            + ("01".equals(MDC.get(StructuredLogKeys.TRACE_FLAGS)) ? "01" : "00"));
        }
    }
}
