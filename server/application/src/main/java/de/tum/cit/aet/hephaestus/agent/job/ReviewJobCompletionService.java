package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCancellationRequested;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompletion;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewWriteGuard;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnServerRole
@ConditionalOnBooleanProperty(name = RuntimeRole.AGENT_ENABLED_PROPERTY)
@RequiredArgsConstructor
public class ReviewJobCompletionService {
    private static final Logger log = LoggerFactory.getLogger(ReviewJobCompletionService.class);
    private final ReviewJobRepository jobs;
    private final ReviewWriteGuard writeGuard;
    private final ReviewJobAssignments assignments;
    private final ReviewAssignmentStore members;
    private final ReviewService reviews;
    private final ReviewJobLifecycleService lifecycle;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.SECONDS)
    @SchedulerLock(name = "practice-job-completion", lockAtMostFor = "PT2M", lockAtLeastFor = "PT1S")
    @WorkspaceAgnostic("Enumerates bounded terminal job identities; each settlement carries its workspace")
    public void tick() {
        for (var candidate : jobs.findTerminalForProcessing(100))
            try {
                transactions.executeWithoutResult(status -> settle(candidate));
            } catch (RuntimeException failure) {
                log.warn(
                        "Review processing could not settle: jobId={}, failure={}",
                        candidate.getId(),
                        failure.getClass().getSimpleName());
            }
    }

    @TransactionalEventListener
    public void cancel(ReviewCancellationRequested event) {
        for (var job : jobs.findByReviewIdAndWorkspaceIdOrderByAvailableAtAsc(event.reviewId(), event.workspaceId()))
            if (job.getStatus() == ReviewJobStatus.QUEUED || job.getStatus() == ReviewJobStatus.RUNNING)
                lifecycle.cancel(event.workspaceId(), job.getId());
    }

    private void settle(ReviewJobRepository.CompletionCandidate candidate) {
        writeGuard.lock(candidate.getWorkspaceId(), candidate.getReviewId());
        var job = jobs.findByIdAndWorkspaceIdForUpdate(candidate.getId(), candidate.getWorkspaceId())
                .orElse(null);
        if (job == null
                || job.getStatus() == ReviewJobStatus.QUEUED
                || job.getStatus() == ReviewJobStatus.RUNNING
                || (job.getProcessingStatus() != ProcessingStatus.NONE
                        && job.getProcessingStatus() != ProcessingStatus.PENDING)) return;
        var review = assignments.review(job);
        boolean admitted = ResultAdmissionService.isAdmitted(job);
        var reason = job.getStatus() == ReviewJobStatus.CANCELLED
                ? job.getCancellationReason() == ReviewJobCancellationReason.NO_SANDBOX_ROOM
                        ? ReviewStopReason.NO_SANDBOX_ROOM
                        : ReviewStopReason.CANCELLED
                : job.getExecutionStartedAt() == null
                        ? ReviewStopReason.PREPARATION_FAILED
                        : ReviewStopReason.EXECUTION_FAILED;
        if (!admitted && (job.getStatus() != ReviewJobStatus.CANCELLED || job.getStartedAt() != null))
            for (var assignment : assignments.admissible(job))
                members.fail(
                        job.getWorkspaceId(),
                        assignment.id(),
                        reason,
                        "The execution ended without an admitted result");
        var capture = job.getCapture();
        boolean runtimeIncomplete =
                admitted && capture != null && !capture.path("nonResults").isEmpty();
        if (runtimeIncomplete) {
            var pinned = assignments.admissible(job);
            for (var nonResult : Objects.requireNonNull(capture).path("nonResults")) {
                var assignment = pinned.stream()
                        .filter(value -> value.practiceSlug()
                                .equals(nonResult.path("practiceSlug").asString()))
                        .findFirst()
                        .orElseThrow();
                members.fail(
                        job.getWorkspaceId(),
                        assignment.id(),
                        ReviewStopReason.valueOf(nonResult.path("reason").asString()),
                        "Runtime execution ended without a result for this practice");
            }
        }
        boolean completed = job.getStatus() == ReviewJobStatus.COMPLETED;
        job.setProcessingStatus(completed ? ProcessingStatus.DONE : ProcessingStatus.FAILED);
        jobs.saveAndFlush(job);
        if (review.kind() == ReviewKind.SCAN) return;
        var output = job.getOutput();
        boolean readinessRefused = output != null
                && (RefusalOutcome.INSUFFICIENT_EVIDENCE
                                .name()
                                .equals(output.path("outcome").asString())
                        || RefusalOutcome.WORKSPACE_REFUSED
                                .name()
                                .equals(output.path("outcome").asString()));
        var status = job.getStatus() == ReviewJobStatus.CANCELLED
                ? ReviewStatus.CANCELLED
                : readinessRefused
                        ? ReviewStatus.PARTIAL
                        : completed
                                ? runtimeIncomplete ? ReviewStatus.PARTIAL : ReviewStatus.COMPLETE
                                : admitted || job.getExecutionStartedAt() != null
                                        ? ReviewStatus.PARTIAL
                                        : ReviewStatus.FAILED;
        var stop = readinessRefused
                ? ReviewStopReason.READINESS_REFUSED
                : completed
                        ? runtimeIncomplete ? ReviewStopReason.EXECUTION_INCOMPLETE : null
                        : job.getStatus() == ReviewJobStatus.TIMED_OUT ? ReviewStopReason.TIMED_OUT : reason;
        reviews.complete(
                job.getWorkspaceId(),
                review.id(),
                new ReviewCompletion(status, stop, clock.instant(), job.getAvailableAt()));
    }
}
