package de.tum.cit.aet.hephaestus.agent.scan;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJobCancellationReason;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobStateConflictException;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompletion;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueue;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewScanReuse;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewScanStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ScanExecutionSnapshot;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

// Product locks and indexes remain the final fence for serial scan work.
@Slf4j
@Component
@ConditionalOnServerRole
@ConditionalOnBooleanProperty(name = RuntimeRole.AGENT_ENABLED_PROPERTY)
@RequiredArgsConstructor
public class ScanDriver {
    private static final int ACTIVE_SCAN_LIMIT = 20;
    private static final Set<ReviewJobStatus> ACTIVE = Set.of(ReviewJobStatus.QUEUED, ReviewJobStatus.RUNNING);
    private final ReviewScanStore scans;
    private final ReviewScanReuse reuse;
    private final ReviewService reviews;
    private final ScanService inventory;
    private final ScanBudget budget;
    private final ReviewQueue queue;
    private final ReviewJobRepository jobs;
    private final ReviewJobLifecycleService lifecycle;
    private final TransactionTemplate transactions;
    private final ScanProperties limits;
    private final Clock clock;

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.SECONDS)
    @SchedulerLock(name = "practice-scan-driver", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1S")
    @WorkspaceAgnostic("Enumerates bounded active scan identities; each mutation carries its workspace")
    public void tick() {
        boolean enumerated = false;
        for (var candidate : scans.active(ACTIVE_SCAN_LIMIT)) {
            if (!candidate.inventoryRecorded()) {
                if (enumerated) continue;
                enumerated = true;
            }
            try {
                if (!candidate.inventoryRecorded()) {
                    boolean started = Boolean.TRUE.equals(transactions.execute(status -> {
                        var state = scans.lock(
                                candidate.review().workspaceId(),
                                candidate.review().id());
                        if (state.inventoryRecorded() || state.status().isTerminal()) return false;
                        if (state.cancellationRequested()
                                || !clock.instant().isBefore(state.createdAt().plus(limits.scanDeadline()))) {
                            advance(state);
                            return false;
                        }
                        return reviews.start(
                                state.review().workspaceId(), state.review().id());
                    }));
                    if (!started) continue;
                    var prepared = inventory.prepare(candidate.review());
                    transactions.executeWithoutResult(status -> {
                        var state = scans.lock(
                                candidate.review().workspaceId(),
                                candidate.review().id());
                        if (state.status().isTerminal()) return;
                        if (!state.cancellationRequested()
                                && clock.instant().isBefore(state.createdAt().plus(limits.scanDeadline())))
                            inventory.record(state.review(), prepared);
                    });
                }
                reuse.reuseListed(
                        candidate.review().workspaceId(),
                        candidate.review().id(),
                        candidate.createdAt().plus(limits.scanDeadline()));
                transactions.executeWithoutResult(status -> advance(scans.lock(
                        candidate.review().workspaceId(), candidate.review().id())));
            } catch (ScanService.EnumerationFailure failure) {
                closeAfterFailure(candidate, failure.reason());
            } catch (RuntimeException failure) {
                log.warn("Scan {} advance failed", candidate.review().id(), failure);
                closeAfterFailure(candidate, ReviewStopReason.ERROR);
            }
        }
    }

    public void advance(ScanExecutionSnapshot state) {
        if (state.status() != ReviewStatus.QUEUED && state.status() != ReviewStatus.RUNNING) return;
        var review = state.review();
        var active = jobs.findActiveId(review.id(), review.workspaceId(), ACTIVE);
        if (active.isPresent()) {
            if (state.cancellationRequested()
                    || !clock.instant().isBefore(state.createdAt().plus(limits.scanDeadline())))
                lifecycle.cancel(review.workspaceId(), active.get());
            return;
        }
        if (jobs.hasUnsettledInvocation(review.workspaceId(), review.id())) return;
        if (state.cancellationRequested()) {
            close(state, ReviewStatus.CANCELLED, ReviewStopReason.CANCELLED);
            return;
        }
        // Capacity ends a job only when no room can come, so the scan's next file would end the same way.
        if (jobs.existsByReviewIdAndWorkspaceIdAndCancellationReason(
                review.id(), review.workspaceId(), ReviewJobCancellationReason.NO_SANDBOX_ROOM)) {
            close(state, ReviewStatus.CANCELLED, ReviewStopReason.NO_SANDBOX_ROOM);
            return;
        }
        if (!clock.instant().isBefore(state.createdAt().plus(limits.scanDeadline()))) {
            close(state, ReviewStatus.PARTIAL, ReviewStopReason.DEADLINE);
            return;
        }
        if (!state.inventoryRecorded()) return;
        if (!state.inventoryComplete()
                || state.listedPracticeIds().size() != review.practices().size())
            throw new ScanService.EnumerationFailure(ReviewStopReason.ENUMERATION_FAILED);
        var pending = scans.pending(review.workspaceId(), review.id(), ScanService.PAGE_SIZE);
        if (pending.isEmpty()) {
            var settled = scans.lock(review.workspaceId(), review.id());
            boolean complete = settled.judged() + settled.reused() == settled.listed() && settled.withheld() == 0;
            close(
                    settled,
                    complete ? ReviewStatus.COMPLETE : ReviewStatus.PARTIAL,
                    complete
                            ? null
                            : settled.withheld() > 0
                                    ? ReviewStopReason.RESULT_WITHHELD
                                    : ReviewStopReason.EXECUTION_INCOMPLETE);
            return;
        }
        long item = pending.getFirst().item().itemId();
        var selected = pending.stream()
                .filter(assignment -> assignment.item().itemId() == item)
                .toList();
        boolean permits;
        try {
            permits = budget.permits(state, selected);
        } catch (ScanUsageUnverifiableException unverifiable) {
            close(state, ReviewStatus.PARTIAL, ReviewStopReason.USAGE_UNVERIFIABLE);
            return;
        } catch (ReviewJobStateConflictException changed) {
            close(state, ReviewStatus.PARTIAL, ReviewStopReason.MODEL_CHANGED);
            return;
        } catch (ScanModelUnavailableException unavailable) {
            close(state, ReviewStatus.PARTIAL, ReviewStopReason.MODEL_UNAVAILABLE);
            return;
        }
        if (!permits) {
            close(state, ReviewStatus.PARTIAL, ReviewStopReason.BUDGET);
            return;
        }
        try {
            queue.enqueue(
                    review,
                    item,
                    selected.stream().map(assignment -> assignment.id()).toList());
        } catch (ReviewJobStateConflictException changed) {
            close(state, ReviewStatus.PARTIAL, ReviewStopReason.MODEL_CHANGED);
        }
    }

    private void closeAfterFailure(ScanExecutionSnapshot candidate, ReviewStopReason reason) {
        transactions.executeWithoutResult(status -> {
            var state = scans.lock(
                    candidate.review().workspaceId(), candidate.review().id());
            if (state.status() != ReviewStatus.QUEUED && state.status() != ReviewStatus.RUNNING) return;
            if (jobs.findActiveId(state.review().id(), state.review().workspaceId(), ACTIVE)
                    .isEmpty())
                close(state, state.inventoryRecorded() ? ReviewStatus.PARTIAL : ReviewStatus.FAILED, reason);
        });
    }

    private void close(ScanExecutionSnapshot state, ReviewStatus status, @Nullable ReviewStopReason reason) {
        var now = clock.instant();
        reviews.complete(
                state.review().workspaceId(),
                state.review().id(),
                new ReviewCompletion(status, reason, now, state.createdAt()));
    }
}
