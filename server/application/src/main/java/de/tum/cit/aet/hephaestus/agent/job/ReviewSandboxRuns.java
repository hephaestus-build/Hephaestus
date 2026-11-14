package de.tum.cit.aet.hephaestus.agent.job;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.handler.DefaultReviewExecutionHandler;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService.AdmissionIdentity;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAttempt;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunState;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxEnvBlocklist;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxGitHardening;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxWorkspaceManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.core.CacheLoads;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final ReviewAttemptPreparation preparation;
    private final ReviewAttemptCustody custody;
    private final ReviewJobTerminal terminal;
    private final ReviewJobRepository jobs;
    private final SandboxWorkspaceManager workspaces;
    private final JobEvidenceFiles evidenceFiles;
    private final SandboxGatewaySessions sessions;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final DefaultReviewExecutionHandler handler;
    private final ReviewAttemptResults results;
    private final ReviewRunKind reviewRuns;
    private final AgentRuns runs;
    /**
     * The measurement of each attempt this replica runs. Preparing one reads the providers and writes the attempt's
     * folder, so it loads outside the map's lock ({@link CacheLoads}).
     */
    private final AsyncCache<AdmissionIdentity, Measurement> measurements =
            Caffeine.newBuilder().buildAsync();

    public ReviewSandboxRuns(
            ReviewJobClaimService claims,
            ReviewAttemptPreparation preparation,
            ReviewAttemptCustody custody,
            ReviewJobTerminal terminal,
            ReviewJobRepository jobs,
            SandboxWorkspaceManager workspaces,
            SandboxGatewaySessions sessions,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            DefaultReviewExecutionHandler handler,
            ReviewAttemptResults results,
            ReviewRunKind reviewRuns,
            AgentRuns runs,
            JobEvidenceFiles evidenceFiles) {
        this.claims = claims;
        this.preparation = preparation;
        this.custody = custody;
        this.terminal = terminal;
        this.jobs = jobs;
        this.workspaces = workspaces;
        this.sessions = sessions;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.handler = handler;
        this.results = results;
        this.reviewRuns = reviewRuns;
        this.runs = runs;
        this.evidenceFiles = evidenceFiles;
    }

    /**
     * An attempt as its sandbox measures it.
     *
     * @param workDeadline when the attempt's work ends, from the start of its execution
     * @param endedBefore the practices that had ended when the measurement started
     */
    record Measurement(
            SandboxSpec spec,
            Instant started,
            Instant workDeadline,
            int endedBefore,
            SandboxGatewaySessions.Session session) {}

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
        return job(run).flatMap(
                        job -> Optional.ofNullable(measurements.synchronous().getIfPresent(identity(run, job)))
                                .map(measurement -> new ProxyAttempt(
                                        ProxyAttempt.Caller.REVIEW,
                                        job.getId(),
                                        job.getWorkspaceId(),
                                        run.fence().epoch(),
                                        job.getRunId(),
                                        measurement.workDeadline())));
    }

    @Override
    public JsonNode context(SandboxRun run) {
        Measurement measurement = measurement(run);
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

    /** The attempt's gateway session, which serves its workspace and takes its result. */
    SandboxGatewaySessions.Session session(SandboxRun run) {
        return measurement(run).session();
    }

    /**
     * The session of the attempt that this replica prepared, which takes its result. A result never prepares: a new
     * capture could differ from the inputs that the sandbox measured, so a result without its prepared attempt ends.
     */
    SandboxGatewaySessions.Session preparedSession(SandboxRun run) {
        ReviewJob job = job(run).orElseThrow(() -> new StaleRunException(run.fence()));
        Measurement measurement = measurements.synchronous().getIfPresent(identity(run, job));
        if (measurement == null) throw new StaleRunException(run.fence());
        return measurement.session();
    }

    /**
     * The attempt's result arrived: the steps after a review Job's sandbox run on it now, outside any transaction, and
     * they end the attempt, hand it on to its composition, or requeue it. Its inputs and its session go after them.
     */
    void measured(SandboxRun run, SandboxResult result) {
        ReviewJob job = job(run).orElseThrow(() -> new StaleRunException(run.fence()));
        var logScope = new ReviewJobLogScope(job, run.fence());
        try {
            AdmissionIdentity attempt = identity(run, job);
            Measurement measurement = measurements.synchronous().getIfPresent(attempt);
            if (measurement == null) throw new StaleRunException(run.fence());
            try {
                results.measured(job.getId(), job, run.fence(), measurement.endedBefore(), result);
            } catch (PessimisticLockingFailureException contention) {
                // The session closes below, so the attempt ends without this result and its run requeues it.
                log.warn(
                        "A review's result was lost to lock contention, so its attempt runs again: jobId={}",
                        job.getId());
                throw contention;
            } finally {
                finish(attempt);
            }
        } finally {
            logScope.close();
        }
    }

    /**
     * Its result already ended the attempt, or handed it on; a run that sent none hands its attempt on, and spends a
     * retry, as a review Job's lost sandbox does.
     */
    @Override
    public boolean end(SandboxRun run, Outcome outcome) {
        Optional<ReviewJob> job = job(run);
        if (job.isEmpty()) return true;
        var logScope = new ReviewJobLogScope(job.get(), run.fence());
        try {
            AdmissionIdentity attempt = identity(run, job.get());
            boolean held =
                    reviewRuns.requeue(job.get().getId(), run.fence(), AgentRuns.Charge.SPEND, Duration.ZERO, null);
            custody.release(attempt);
            Measurement measurement = measurements.synchronous().asMap().remove(attempt);
            if (measurement != null) close(measurement.session());
            return held
                    || runs.find(new AgentRunRef(
                                    run.fence().runId(), run.fence().workspaceId()))
                            .filter(found -> found.getState() == AgentRunState.ENDED)
                            .isPresent();
        } finally {
            logScope.close();
        }
    }

    private void finish(AdmissionIdentity attempt) {
        custody.release(attempt);
        Measurement measurement = measurements.synchronous().asMap().remove(attempt);
        if (measurement != null) close(measurement.session());
    }

    private static void close(SandboxGatewaySessions.Session session) {
        try {
            session.close();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The attempt's measurement, prepared by the first request of its run on this replica. */
    private Measurement measurement(SandboxRun run) {
        ReviewJob job = job(run).orElseThrow(() -> new StaleRunException(run.fence()));
        AdmissionIdentity attempt = identity(run, job);
        return CacheLoads.get(measurements, attempt, key -> prepare(run, job, key));
    }

    private Measurement prepare(SandboxRun run, ReviewJob job, AdmissionIdentity attempt) {
        var logScope = new ReviewJobLogScope(job, run.fence());
        try {
            var measurement = new AtomicReference<Measurement>();
            custody.hold(attempt, () -> {
                ConfigSnapshot snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
                var stage = new AtomicReference<>(PreparationFailure.Stage.INPUTS);
                ReviewAttemptPreparation.PreparedSandbox prepared;
                try {
                    prepared = preparation.prepare(job.getId(), job, run.fence(), snapshot, stage);
                } catch (ResultAdmissionService.StaleAttemptException lost) {
                    throw new StaleRunException(run.fence(), lost);
                } catch (RuntimeException failed) {
                    results.unprepared(job, run.fence(), PreparationFailure.of(stage.get(), failed), failed);
                    throw new StaleRunException(run.fence(), failed);
                }
                PreparedJobInputs inputs = prepared.stagedInputs();
                boolean transferred = false;
                try {
                    SandboxSpec spec = prepared.spec();
                    if (spec == null) {
                        // Nothing is left to measure: the attempt completes without its sandbox.
                        transactions.executeWithoutResult(status -> {
                            if (terminal.transition(
                                            job.getId(), run.fence(), ReviewJobStatus.COMPLETED, Instant.now(), null)
                                    > 0)
                                jobs.findById(job.getId()).ifPresent(completed -> {
                                    completed.setProcessingStatus(ProcessingStatus.PENDING);
                                    jobs.saveAndFlush(completed);
                                });
                        });
                        custody.release(attempt);
                        throw new StaleRunException(run.fence());
                    }
                    Instant started = Instant.now();
                    Integer marked = transactions.execute(status ->
                            jobs.markExecutionStarted(job.getId(), job.getRunId(), job.getAttempt(), started));
                    if (marked == null || marked != 1) {
                        custody.release(attempt);
                        throw new StaleRunException(run.fence());
                    }
                    try {
                        Path input = workspaces.createInputTar(
                                evidenceFiles.archiveDirectory(job),
                                spec.inputFiles(),
                                spec.inputFilesOnDisk(),
                                spec.inputDirectories());
                        try {
                            var session = sessions.registerAttempt(
                                    job.getId(),
                                    job.getAttempt(),
                                    spec.image(),
                                    UUID.randomUUID().toString(),
                                    input,
                                    "out");
                            measurement.set(new Measurement(
                                    spec,
                                    started,
                                    started.plusSeconds(snapshot.timeoutSeconds()),
                                    handler.endedPractices(job),
                                    session));
                            var retained = new PreparedJobInputs(
                                    inputs.filesOnDisk(),
                                    inputs.directories(),
                                    List.of(() -> close(session), inputs),
                                    inputs.folderIndex(),
                                    inputs.automatedReviewReadinessReport(),
                                    inputs.copiesExpireAt());
                            transferred = true;
                            return retained;
                        } catch (IOException | RuntimeException failure) {
                            Files.deleteIfExists(input);
                            throw failure;
                        }
                    } catch (IOException failure) {
                        throw unstarted(run, job, attempt, new UncheckedIOException(failure));
                    } catch (RuntimeException failure) {
                        throw unstarted(run, job, attempt, failure);
                    }
                } finally {
                    if (!transferred) inputs.close();
                }
            });
            return Objects.requireNonNull(measurement.get());
        } finally {
            logScope.close();
        }
    }

    /** Its execution started, and its sandbox cannot measure it: the attempt ends, or goes back to the queue. */
    private StaleRunException unstarted(
            SandboxRun run, ReviewJob job, AdmissionIdentity attempt, RuntimeException failure) {
        custody.release(attempt);
        results.unprepared(job, run.fence(), null, failure);
        return new StaleRunException(run.fence(), failure);
    }

    /** The job that the run measures, while the run's claim still holds it. */
    private Optional<ReviewJob> job(SandboxRun run) {
        return jobs.findByRunIdAndWorkspaceId(run.fence().runId(), run.fence().workspaceId())
                .filter(job ->
                        job.getAttempt() == ReviewRunKind.attempt(run.fence().epoch()));
    }

    private static AdmissionIdentity identity(SandboxRun run, ReviewJob job) {
        return new AdmissionIdentity(
                job.getId(),
                job.getWorkspaceId(),
                job.getAttempt(),
                Objects.requireNonNull(run.fence().runId()));
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
