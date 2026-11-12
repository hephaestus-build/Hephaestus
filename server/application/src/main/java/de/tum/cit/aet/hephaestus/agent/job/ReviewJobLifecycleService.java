package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReviewJobLifecycleService {

    private static final Set<ReviewJobStatus> ACTIVE_STATUSES = Set.of(ReviewJobStatus.QUEUED, ReviewJobStatus.RUNNING);

    private final ReviewJobRepository jobRepository;
    private final TransactionTemplate transactionTemplate;
    private final ReviewJobTelemetry jobTelemetry;
    private final AgentRuns runs;

    public ReviewJobLifecycleService(
            ReviewJobRepository jobRepository,
            TransactionTemplate transactionTemplate,
            ReviewJobTelemetry jobTelemetry,
            AgentRuns runs) {
        this.jobRepository = jobRepository;
        this.runs = runs;
        this.transactionTemplate = transactionTemplate;
        this.jobTelemetry = jobTelemetry;
    }

    private record CancelOutcome(ReviewJob job, boolean stopContainer) {}

    /**
     * Idempotent when the job is already CANCELLED; throws 409 for any other terminal state.
     *
     * <p>The run's cancellation settles the calls that are still open in the status-transition transaction, so
     * accounting and the state change stand or fall together.
     */
    public ReviewJob cancel(Long workspaceId, UUID jobId) {
        CancelOutcome outcome = transactionTemplate.execute(status -> cancelTransition(workspaceId, jobId));

        // The gateway refuses the cancelled attempt's next call at once, and its sandbox's driver stops the run at its
        // next lease renewal.
        return outcome.job();
    }

    private ReviewJob requireJob(Long workspaceId, UUID jobId) {
        return jobRepository
                .findByIdAndWorkspaceId(jobId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("ReviewJob", jobId.toString()));
    }

    private int casToCancelled(UUID jobId) {
        return jobRepository.transitionToCancelled(
                jobId, Instant.now(), null, ReviewJobCancellationReason.CANCELLED_BY_REQUESTER, ACTIVE_STATUSES);
    }

    private CancelOutcome cancelTransition(Long workspaceId, UUID jobId) {
        ReviewJob job = requireJob(workspaceId, jobId);

        if (job.getStatus() == ReviewJobStatus.CANCELLED) {
            return new CancelOutcome(job, false);
        }

        if (!ACTIVE_STATUSES.contains(job.getStatus())) {
            throw new ReviewJobStateConflictException("This review already finished. You cannot cancel it.");
        }

        if (casToCancelled(jobId) == 0) {
            ReviewJob raced = requireJob(workspaceId, jobId);
            if (!ACTIVE_STATUSES.contains(raced.getStatus())) {
                throw new ReviewJobStateConflictException("This review finished before the server could cancel it.");
            }
            // Back inside the CAS window, so a concurrent claim moved it there; retry once and then
            // report whatever state the loser observes rather than spinning against the executor.
            if (casToCancelled(jobId) == 0) {
                ReviewJob racedAgain = requireJob(workspaceId, jobId);
                if (racedAgain.getStatus() != ReviewJobStatus.CANCELLED) {
                    throw new ReviewJobStateConflictException(
                            "This review changed state while the server canceled it. Reload the review and try again.");
                }
                return new CancelOutcome(racedAgain, false);
            }
        }

        ReviewJob fresh = requireJob(workspaceId, jobId);
        // The run ends with its job; its holder's next fenced write then changes nothing.
        runs.cancel(fresh.run());
        jobTelemetry.terminal(fresh, ReviewJobStatus.CANCELLED, ReviewJobTelemetry.age(fresh));
        return new CancelOutcome(fresh, true);
    }
}
