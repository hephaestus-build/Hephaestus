package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService.AdmissionIdentity;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunHandler;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunStopReason;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The reaper's side of a review's composition run. It drives the review job that its review run admitted, which keeps
 * its attempt counter and retry limit across both runs (ADR 0061 § Retry). So a lost or ended composition moves the
 * job with its run: a lost lease continues on a successor with the same retry budget, and a run that ends
 * without its holder closes the review as partial, because nothing confirmed each destination. Either way the attempt's
 * held inputs go, where this replica holds them.
 */
@Component
@ConditionalOnCaptureRole
@WorkspaceAgnostic("Each call names a run, which carries its workspace")
public class ReviewComposeRunKind implements AgentRunHandler {
    private final ReviewRunKind review;
    private final ReviewJobRepository jobs;
    private final ReviewAttemptCustody custody;

    ReviewComposeRunKind(ReviewRunKind review, ReviewJobRepository jobs, ReviewAttemptCustody custody) {
        this.review = review;
        this.jobs = jobs;
        this.custody = custody;
    }

    @Override
    public AgentRunKind kind() {
        return AgentRunKind.REVIEW_COMPOSE;
    }

    @Override
    public int retries() {
        return review.retries();
    }

    @Override
    public void lock(AgentRunRef run) {
        review.lock(run);
    }

    @Override
    public LossOutcome lost(AgentRunFence fence) {
        release(fence.runId(), fence.workspaceId(), fence.epoch());
        return review.continueExpiredComposition(fence);
    }

    @Override
    public void ended(AgentRunRef run, int lastEpoch, AgentRunStopReason reason) {
        release(run.id(), run.workspaceId(), lastEpoch);
        review.ended(run, lastEpoch, reason);
    }

    private void release(UUID runId, long workspaceId, int epoch) {
        jobs.findIdByRun(runId, workspaceId)
                .ifPresent(jobId -> custody.release(
                        new AdmissionIdentity(jobId, workspaceId, ReviewRunKind.attempt(epoch), runId)));
    }
}
