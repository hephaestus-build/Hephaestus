package de.tum.cit.aet.hephaestus.practices.review.schedule;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeReviewReadiness;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchPauseReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBudget;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@Slf4j
@WorkspaceAgnostic("Selects active batches across workspaces; each mutation retains its workspace")
public class ReviewBatchDriver {
    private final ReviewBatchRepository batches;
    private final ReviewBatchScopeQuery scope;
    private final ReviewBatchSubmitter submitter;
    private final WorkspaceRepository workspaces;
    private final ReviewBudget budget;
    private final PracticeReviewReadiness readiness;
    private final ReviewBatchProperties properties;
    private final Clock clock;
    private final ReviewRepository reviews;
    private final ReviewBatchSpend spend;

    @Scheduled(fixedDelay = 120000, initialDelay = 120000)
    @SchedulerLock(name = "review-batch-driver", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void tick() {
        var running = batches.findByStatusOrderByUpdatedAtAscIdAsc(ReviewBatchStatus.RUNNING, PageRequest.ofSize(5));
        var automaticallyPaused = batches.findByStatusAndPauseReasonNotOrderByUpdatedAtAscIdAsc(
                ReviewBatchStatus.PAUSED, ReviewBatchPauseReason.PAUSED_BY_ADMIN, PageRequest.ofSize(5));
        for (var batch :
                Stream.concat(running.stream(), automaticallyPaused.stream()).toList()) {
            try {
                advance(batch);
            } catch (RuntimeException failure) {
                log.warn(
                        "Could not advance review batch {} in workspace {}",
                        batch.getId(),
                        batch.getWorkspaceId(),
                        failure);
            }
        }
    }

    void advance(ReviewBatch batch) {
        boolean automaticallyPaused = batch.getStatus() == ReviewBatchStatus.PAUSED
                && batch.getPauseReason() != ReviewBatchPauseReason.PAUSED_BY_ADMIN;
        if (batch.getStatus() != ReviewBatchStatus.RUNNING && !automaticallyPaused) return;
        var workspace = workspaces.findById(batch.getWorkspaceId()).orElse(null);
        boolean scan = batch.getKind().equals("scm.repository");
        if (scan
                && workspace != null
                && workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE
                && reviews.existsByWorkspaceIdAndKindAndStatusIn(
                        batch.getWorkspaceId(), ReviewKind.SCAN, List.of(ReviewStatus.QUEUED, ReviewStatus.RUNNING))) {
            batch.setUpdatedAt(clock.instant());
            batches.save(batch);
            return;
        }
        var ids = scope.next(
                batch.getWorkspaceId(), batch.getId(), batch.getCursorItemId(), scan ? 1 : properties.batchSize());
        if (ids.isEmpty()) {
            batch.setStatus(ReviewBatchStatus.COMPLETED);
            batch.setPauseReason(null);
            batch.setFinishedAt(clock.instant());
            batch.setUpdatedAt(clock.instant());
            batches.save(batch);
            return;
        }
        var remaining = spend.remaining(batch);
        if (batch.getOrigin() == ReviewBatchOrigin.SCHEDULE && remaining.verified() && remaining.exhausted()) {
            batch.setStatus(ReviewBatchStatus.BUDGET_EXHAUSTED);
            batch.setPauseReason(null);
            batch.setFinishedAt(clock.instant());
            batch.setUpdatedAt(clock.instant());
            batches.save(batch);
            return;
        }
        ReviewBatchPauseReason reason = workspace == null || workspace.getStatus() != Workspace.WorkspaceStatus.ACTIVE
                ? ReviewBatchPauseReason.WORKSPACE_UNAVAILABLE
                : !readiness.hasRunnableAgent(batch.getWorkspaceId())
                        ? ReviewBatchPauseReason.REVIEW_MODEL_UNBOUND
                        : remaining.exhausted()
                                        || !budget.canRequest(
                                                batch.getWorkspaceId(),
                                                scan ? ReviewKind.SCAN : ReviewKind.ITEM,
                                                remaining.remainingUsd())
                                ? ReviewBatchPauseReason.BUDGET_EXHAUSTED
                                : null;
        if (reason != null) {
            batch.setStatus(ReviewBatchStatus.PAUSED);
            batch.setPauseReason(reason);
            batch.setUpdatedAt(clock.instant());
            batches.save(batch);
            return;
        }
        batch.setStatus(ReviewBatchStatus.RUNNING);
        batch.setPauseReason(null);
        for (long itemId : ids) {
            try {
                switch (submitter.offer(batch, itemId, remaining.remainingUsd())) {
                    case ReviewBatchSubmitter.SubmissionOutcome.Submitted ignored ->
                        batch.setSubmittedCount(batch.getSubmittedCount() + 1);
                    case ReviewBatchSubmitter.SubmissionOutcome.Skipped ignored ->
                        batch.setSkippedCount(batch.getSkippedCount() + 1);
                    case ReviewBatchSubmitter.SubmissionOutcome.Deferred ignored -> {
                        batch.setUpdatedAt(clock.instant());
                        batches.save(batch);
                        return;
                    }
                    case ReviewBatchSubmitter.SubmissionOutcome.Paused paused -> {
                        batch.setStatus(ReviewBatchStatus.PAUSED);
                        batch.setPauseReason(paused.reason());
                        batch.setUpdatedAt(clock.instant());
                        batches.save(batch);
                        return;
                    }
                }
            } catch (RuntimeException failure) {
                log.warn("Could not request review for item {} in batch {}", itemId, batch.getId(), failure);
                batch.setFailedCount(batch.getFailedCount() + 1);
            }
            batch.setCursorItemId(itemId);
        }
        batch.setUpdatedAt(clock.instant());
        batches.save(batch);
    }
}
