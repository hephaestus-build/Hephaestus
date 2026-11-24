package de.tum.cit.aet.hephaestus.agent.job;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.context.AttemptFolderBusyException;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService.AdmissionIdentity;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxWorkspaceManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.core.CacheLoads;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusedException;
import de.tum.cit.aet.hephaestus.core.lock.ClusterLock;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * The measurement of each review attempt that this replica runs: the attempt's sandbox spec, its work deadline and its
 * gateway session. The first request of the attempt's run prepares it, under a cluster lock per attempt, so one replica
 * at a time prepares an attempt. A preparation that needs a fresh epoch requeues the attempt and releases its custody.
 */
@Component
@ConditionalOnAgentRuntime
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
@Profile("!specs & !cds-training")
public class ReviewAttemptMeasurements {

    private static final Logger log = LoggerFactory.getLogger(ReviewAttemptMeasurements.class);

    private final ReviewAttemptPreparation preparation;
    private final ReviewAttemptCustody custody;
    private final ReviewJobRepository jobs;
    private final SandboxWorkspaceManager workspaces;
    private final JobEvidenceFiles evidenceFiles;
    private final SandboxGatewaySessions sessions;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final ReviewAttemptResults results;
    private final ReviewRunKind reviewRuns;
    private final AgentRuns runs;
    private final ClusterLock preparationLocks;
    /**
     * The measurement of each attempt this replica runs. Preparing one reads the providers and writes the attempt's
     * folder, so it loads outside the map's lock ({@link CacheLoads}).
     */
    private final AsyncCache<AdmissionIdentity, Measurement> measurements =
            Caffeine.newBuilder().buildAsync();

    public ReviewAttemptMeasurements(
            ReviewAttemptPreparation preparation,
            ReviewAttemptCustody custody,
            ReviewJobRepository jobs,
            SandboxWorkspaceManager workspaces,
            JobEvidenceFiles evidenceFiles,
            SandboxGatewaySessions sessions,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            ReviewAttemptResults results,
            ReviewRunKind reviewRuns,
            AgentRuns runs,
            ClusterLock preparationLocks) {
        this.preparation = preparation;
        this.custody = custody;
        this.jobs = jobs;
        this.workspaces = workspaces;
        this.evidenceFiles = evidenceFiles;
        this.sessions = sessions;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.results = results;
        this.reviewRuns = reviewRuns;
        this.runs = runs;
        this.preparationLocks = preparationLocks;
    }

    /**
     * An attempt as its sandbox measures it.
     *
     * @param workDeadline when the attempt's work ends, from the start of its execution
     */
    record Measurement(SandboxSpec spec, Instant workDeadline, SandboxGatewaySessions.Session session) {}

    private static void close(SandboxGatewaySessions.Session session) {
        try {
            session.close();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The attempt's measurement, prepared by the first request of its run on this replica. */
    Measurement get(SandboxRun run) {
        ReviewJob job = job(jobs, run).orElseThrow(() -> new StaleRunException(run.fence()));
        AdmissionIdentity attempt = identity(run, job);
        try {
            return CacheLoads.get(
                    measurements,
                    attempt,
                    key -> preparationLocks
                            .call(
                                    "review-preparation:" + key.workspaceId() + ":" + key.runId() + ":" + key.attempt(),
                                    Duration.ofSeconds(ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper)
                                            .timeoutSeconds()),
                                    () -> prepare(
                                            run,
                                            job(jobs, run).orElseThrow(() -> new StaleRunException(run.fence())),
                                            key))
                            .orElseThrow(() ->
                                    new AttemptFolderBusyException("Another server is preparing this review attempt")));
        } catch (AttemptFolderBusyException
                | ClusterLock.UnavailableException
                | PessimisticLockingFailureException unavailable) {
            throw preparationUnavailable(unavailable);
        }
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
                } catch (ReviewAttemptPreparation.FreshEpochRequired incomplete) {
                    throw restart(run, job, attempt, incomplete);
                } catch (AttemptFolderBusyException | PessimisticLockingFailureException busy) {
                    throw busy;
                } catch (ResultAdmissionService.StaleAttemptException lost) {
                    throw new StaleRunException(run.fence(), lost);
                } catch (AuthorityRefusedException refused) {
                    // The access narrowed: no preparation failed, and the route ends the run with that reason.
                    throw refused;
                } catch (RuntimeException failed) {
                    throw preparationFailed(run, job, stage.get(), failed);
                }
                PreparedJobInputs inputs = prepared.stagedInputs();
                boolean transferred = false;
                try {
                    SandboxSpec spec = prepared.spec();
                    if (spec == null) {
                        // Nothing is left to measure: the attempt completes without its sandbox.
                        results.endUnmeasured(job.getId(), run.fence(), prepared.sealedStaged());
                        custody.release(attempt);
                        throw new StaleRunException(run.fence());
                    }
                    if (!runs.isCurrent(run.fence().run(), run.fence().epoch())) {
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
                            var session = sessions.register(job.getId(), input);
                            measurement.set(
                                    new Measurement(spec, Objects.requireNonNull(spec.workDeadline()), session));
                            var retained = new PreparedJobInputs(
                                    inputs.filesOnDisk(),
                                    inputs.directories(),
                                    List.of(
                                            () -> measurements.synchronous().invalidate(attempt),
                                            () -> close(session),
                                            inputs),
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

    /** A lost commit reply is resolved from the durable checkpoint before any terminal write. */
    private RuntimeException preparationFailed(
            SandboxRun run, ReviewJob job, PreparationFailure.Stage stage, RuntimeException failure) {
        Optional<Boolean> published;
        try {
            published = preparation.published(job.getId(), run.fence());
        } catch (RuntimeException unreadable) {
            if (!unreadable.equals(failure)) unreadable.addSuppressed(failure);
            return preparationUnavailable(unreadable);
        }
        if (published.isEmpty()) return new StaleRunException(run.fence(), failure);
        if (stage == PreparationFailure.Stage.PROVENANCE
                && published.get()
                && !(failure instanceof JobPreparationException)) return preparationUnavailable(failure);
        results.unprepared(job, run.fence(), PreparationFailure.of(stage, failure), failure);
        return new StaleRunException(run.fence(), failure);
    }

    private static ErrorResponseException preparationUnavailable(RuntimeException cause) {
        var busy = new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, cause);
        busy.setDetail("Review preparation is temporarily unavailable");
        busy.getHeaders().set("Retry-After", "1");
        return busy;
    }

    StaleRunException restart(
            SandboxRun run,
            ReviewJob job,
            AdmissionIdentity attempt,
            ReviewAttemptPreparation.FreshEpochRequired incomplete) {
        transactions.executeWithoutResult(status -> {
            if (!reviewRuns.requeue(job.getId(), run.fence(), AgentRuns.Charge.SPEND, Duration.ZERO, null))
                throw new StaleRunException(run.fence());
        });
        custody.release(attempt);
        log.atInfo()
                .addKeyValue("event.name", "agent.review.preparation_restarted")
                .log("Review preparation requires a fresh epoch");
        return new StaleRunException(run.fence(), incomplete);
    }

    /** Its execution started, and its sandbox cannot measure it: the attempt ends, or goes back to the queue. */
    private StaleRunException unstarted(
            SandboxRun run, ReviewJob job, AdmissionIdentity attempt, RuntimeException failure) {
        custody.release(attempt);
        results.unprepared(job, run.fence(), null, failure);
        return new StaleRunException(run.fence(), failure);
    }

    /** The job that the run measures, while the run's claim still holds it. */
    static Optional<ReviewJob> job(ReviewJobRepository jobs, SandboxRun run) {
        return jobs.findByRunIdAndWorkspaceId(run.fence().runId(), run.fence().workspaceId())
                .filter(job ->
                        job.getAttempt() == ReviewRunKind.attempt(run.fence().epoch()));
    }

    static AdmissionIdentity identity(SandboxRun run, ReviewJob job) {
        return new AdmissionIdentity(
                job.getId(),
                job.getWorkspaceId(),
                job.getAttempt(),
                Objects.requireNonNull(run.fence().runId()));
    }
}
