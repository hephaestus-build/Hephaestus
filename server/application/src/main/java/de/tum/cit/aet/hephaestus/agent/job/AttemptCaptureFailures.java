package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService.AdmissionIdentity;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.integration.core.spi.CheckoutUnverifiedException;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Ends an attempt whose folder a server could not rebuild while it admitted the attempt's results: the capture failure
 * that a capture-stage {@link PreparationFailure} records, with the same diagnosis on the job. The write is fenced on
 * the attempt's run and epoch, so a stale attempt writes nothing.
 */
@Component
@ConditionalOnAgentRuntime
public class AttemptCaptureFailures {
    private final ReviewJobRepository jobs;
    private final AgentRuns runs;
    private final ReviewJobTelemetry telemetry;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    AttemptCaptureFailures(
            ReviewJobRepository jobs,
            AgentRuns runs,
            ReviewJobTelemetry telemetry,
            ObjectMapper mapper,
            PlatformTransactionManager transactions) {
        this.jobs = jobs;
        this.runs = runs;
        this.telemetry = telemetry;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactions);
    }

    /** @return whether this call ended the attempt; false when its run or attempt no longer holds the job */
    @WorkspaceAgnostic("The attempt credential supplies the workspace; every write is fenced on it")
    public boolean fail(AdmissionIdentity identity, CheckoutUnverifiedException failure) {
        var preparation = PreparationFailure.of(PreparationFailure.Stage.INPUTS, failure);
        Boolean ended = transactions.execute(status -> {
            int updated = jobs.transitionStatusOwnedBy(
                    identity.jobId(),
                    ReviewJobStatus.FAILED,
                    Instant.now(),
                    preparation.message(),
                    identity.runId(),
                    identity.attempt());
            if (updated == 0) return false;
            ReviewJobTerminal.endRun(
                    runs,
                    new AgentRunFence(
                            identity.runId(), identity.workspaceId(), ReviewRunKind.epoch(identity.attempt())),
                    ReviewJobStatus.FAILED);
            jobs.recordPreparationFailure(
                    identity.jobId(),
                    identity.workspaceId(),
                    identity.attempt(),
                    identity.runId(),
                    preparation.diagnosis(mapper, identity.attempt()));
            return true;
        });
        if (!Boolean.TRUE.equals(ended)) return false;
        jobs.findByIdAndWorkspaceId(identity.jobId(), identity.workspaceId())
                .ifPresent(job -> telemetry.preparationFailed(job, preparation, ReviewJobStatus.FAILED.name()));
        return true;
    }
}
