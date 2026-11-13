package de.tum.cit.aet.hephaestus.practices.review.schedule;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewScheduleBatchOpener {
    private final ReviewScheduleRepository schedules;
    private final ReviewBatchRepository batches;
    private final ReviewBatchService batchService;
    private final ReviewBatchScopeQuery scope;
    private final ReviewBatchCostEstimator costs;
    private final ReviewBatchProperties properties;
    private final WorkspaceQueries workspaces;
    private final ReviewRepository reviews;

    @Transactional
    @WorkspaceAgnostic("Opens a globally selected due schedule using its stored workspace")
    public ReviewScheduleOutcome openDueRun(UUID scheduleId, Instant now) {
        var schedule = schedules.findById(scheduleId).orElseThrow();
        if (!schedule.isEnabled()) return ReviewScheduleOutcome.SKIPPED_DISABLED;
        schedule.setNextRunAt(now.plus(schedule.getCadence().interval()));
        schedule.setLastRunAt(now);
        schedule.setUpdatedAt(now);
        var workspace = workspaces.findById(schedule.getWorkspaceId()).orElse(null);
        if (workspace == null || !workspace.active()) return ReviewScheduleOutcome.SKIPPED_WORKSPACE_UNAVAILABLE;
        if (batches.existsByWorkspaceIdAndStatusIn(
                schedule.getWorkspaceId(), List.of(ReviewBatchStatus.RUNNING, ReviewBatchStatus.PAUSED)))
            return ReviewScheduleOutcome.SKIPPED_BATCH_UNDER_WAY;
        if (schedule.getKind().equals("scm.repository")
                && reviews.existsByWorkspaceIdAndKindAndStatusIn(
                        schedule.getWorkspaceId(), ReviewKind.SCAN, List.of(ReviewStatus.QUEUED, ReviewStatus.RUNNING)))
            return ReviewScheduleOutcome.SKIPPED_BATCH_UNDER_WAY;
        Instant
                from =
                        schedule.getLookbackDays() == null
                                ? null
                                : now.minus(Duration.ofDays(schedule.getLookbackDays())),
                to = from == null ? null : now;
        var selected = scope.select(schedule.getWorkspaceId(), schedule.getKind(), from, to, properties.maxItems() + 1);
        if (selected.isEmpty()) return ReviewScheduleOutcome.SKIPPED_EMPTY_SCOPE;
        if (selected.size() > properties.maxItems()) return ReviewScheduleOutcome.SKIPPED_SCOPE_TOO_LARGE;
        var batch = ReviewBatch.builder()
                .id(UUID.randomUUID())
                .workspaceId(schedule.getWorkspaceId())
                .kind(schedule.getKind())
                .origin(ReviewBatchOrigin.SCHEDULE)
                .scheduleId(scheduleId)
                .fromAt(from)
                .toAt(to)
                .status(ReviewBatchStatus.RUNNING)
                .budgetUsd(schedule.getBudgetUsd())
                .estimatedItems(selected.size())
                .estimatedCostUsd(
                        costs.estimateTotalUsd(schedule.getWorkspaceId(), schedule.getKind(), selected.size()))
                .startedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        batches.saveAndFlush(batch);
        batchService.freezeTargets(batch, selected);
        return ReviewScheduleOutcome.OPENED;
    }

    @Transactional
    @WorkspaceAgnostic("Defers a globally selected schedule after an unsuccessful open")
    public void deferAfterFailure(UUID id, Instant now) {
        schedules.findById(id).ifPresent(s -> {
            s.setNextRunAt(now.plusSeconds(120));
            s.setUpdatedAt(now);
        });
    }
}
