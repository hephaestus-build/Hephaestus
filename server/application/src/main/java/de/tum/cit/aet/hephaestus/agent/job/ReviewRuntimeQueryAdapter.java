package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueueJob;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueueSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingFactsQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingStage;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingSums;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewUsageAttempt;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewUsageSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnCaptureRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewRuntimeQueryAdapter implements ReviewRuntimeQuery {
    private final ReviewJobRepository jobs;
    private final ReviewAssignmentQuery assignments;
    private final ReviewRuntimeRepository runtime;
    private final ReviewTimingFactsQuery timingFacts;

    @Override
    public Optional<ReviewRuntimeSnapshot> runtime(long workspaceId, UUID reviewId) {
        var rows = runtime.snapshots(workspaceId, reviewId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(new ReviewRuntimeSnapshot(rows));
    }

    @Override
    public Map<UUID, Map<ReviewJobStatus, Long>> jobCounts(long workspaceId, Collection<UUID> reviewIds) {
        if (reviewIds.isEmpty()) return Map.of();
        Map<UUID, Map<ReviewJobStatus, Long>> counts = new HashMap<>();
        runtime.jobCounts(workspaceId, reviewIds)
                .forEach(row -> counts.computeIfAbsent(row.reviewId(), ignored -> new EnumMap<>(ReviewJobStatus.class))
                        .put(row.status(), row.count()));
        return counts;
    }

    @Override
    public Set<UUID> activeJobIds(long workspaceId, UUID reviewId) {
        return jobs.findActiveId(reviewId, workspaceId, Set.of(ReviewJobStatus.QUEUED, ReviewJobStatus.RUNNING))
                .map(Set::of)
                .orElseGet(Set::of);
    }

    @Override
    public Set<UUID> unsettledJobIds(long workspaceId, UUID reviewId) {
        return runtime.unsettledJobIds(workspaceId, reviewId);
    }

    @Override
    public ReviewUsageSnapshot usage(long workspaceId, UUID reviewId) {
        return summarizeUsage(runtime.usage(workspaceId, reviewId));
    }

    @Override
    public Map<UUID, ReviewUsageSnapshot> usageByReview(long workspaceId, Set<UUID> reviewIds) {
        if (reviewIds.isEmpty()) return Map.of();
        return runtime.usageByReview(workspaceId, reviewIds).entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> summarizeUsage(entry.getValue())));
    }

    private static ReviewUsageSnapshot summarizeUsage(List<ReviewUsageAttempt> attempts) {
        boolean priced =
                attempts.stream().allMatch(a -> a.costUsd() != null && a.pricingState() != UsagePricingState.UNPRICED);
        BigDecimal cost = priced
                ? attempts.stream()
                        .map(a -> Objects.requireNonNull(a.costUsd()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                : null;
        return new ReviewUsageSnapshot(
                cost,
                priced,
                attempts.stream().mapToLong(ReviewUsageAttempt::calls).sum(),
                attempts.stream().mapToLong(ReviewUsageAttempt::inputTokens).sum(),
                attempts.stream().mapToLong(ReviewUsageAttempt::outputTokens).sum(),
                attempts);
    }

    @Override
    public ReviewQueueSnapshot queue(long workspaceId, Instant asOf, int headLimit) {
        if (headLimit < 0 || headLimit > 100) throw new IllegalArgumentException("Invalid queue head limit");
        var totals = runtime.queueCounts(workspaceId, asOf);
        long workers = runtime.liveWorkers(asOf);
        var queued = headLimit == 0
                ? List.<ReviewJob>of()
                : jobs.findByWorkspaceIdAndStatusInOrderByAvailableAtAscIdAsc(
                        workspaceId, Set.of(ReviewJobStatus.QUEUED, ReviewJobStatus.RUNNING), Limit.of(headLimit));
        var head = queued.stream()
                .map(job -> {
                    var id = job.getId();
                    var people = job.getInput().assignments().stream()
                            .flatMap(assignmentId -> assignments.find(workspaceId, assignmentId).stream())
                            .map(a -> a.developerId())
                            .distinct()
                            .toList();
                    Long developer = people.size() == 1 ? people.getFirst() : null;
                    return new ReviewQueueJob(
                            id,
                            job.getReviewId(),
                            job.getInput().itemId(),
                            developer,
                            job.getStatus(),
                            ReviewJob.HOLD_REASON_BUDGET.equals(job.getHoldReason()),
                            job.getAvailableAt());
                })
                .toList();
        return new ReviewQueueSnapshot(
                asOf,
                totals.running(),
                workers,
                totals.waiting(),
                totals.oldest(),
                totals.held(),
                head,
                totals.hour(),
                totals.day());
    }

    @Override
    public ReviewTimingSnapshot timing(long workspaceId, Instant from, Instant to, Instant longWaitThreshold) {
        if (!from.isBefore(to)) throw new IllegalArgumentException("The timing window is empty");
        var product = timingFacts;
        var timelines = product.window(workspaceId, from, to);
        var reviewIds = timelines.stream().map(fact -> fact.reviewId()).toList();
        var rows = runtime.timingJobs(workspaceId, reviewIds);
        var byReview = timelines.stream().collect(Collectors.toMap(fact -> fact.reviewId(), Function.identity()));
        var samples = new HashMap<String, List<Double>>();
        double queue = 0,
                preparation = 0,
                model = 0,
                unsplit = 0,
                finishedQueue = 0,
                finishedPreparation = 0,
                finishedModel = 0,
                finishedUnsplit = 0;
        long longWait = 0;
        boolean publicBreakdownKnown = true, finishedBreakdownKnown = true;
        for (var row : rows) {
            if (row.status() == ReviewJobStatus.QUEUED
                    && !ReviewJob.HOLD_REASON_BUDGET.equals(row.holdReason())
                    && row.availableAt().isBefore(longWaitThreshold)) longWait++;
            var fact = Objects.requireNonNull(byReview.get(row.reviewId()));
            if (row.attempt() > 0) {
                // Requeue replaces these timestamps; the earlier intervals are not retained.
                if (fact.firstAutomaticPostedAt() != null && fact.publicCompletedAt() != null)
                    publicBreakdownKnown = false;
                if (fact.finished()) finishedBreakdownKnown = false;
                continue;
            }
            var start = row.startedAt();
            if (start == null) continue;
            sample(samples, "QUEUE", seconds(row.availableAt(), start));
            var publicAt = fact.publicCompletedAt();
            var end = row.completedAt();
            var execution = row.executionStartedAt();
            var measuredEnd = end == null ? publicAt : end;
            if (measuredEnd != null) {
                if (execution == null) sample(samples, "RUN_UNSPLIT", seconds(start, measuredEnd));
                else {
                    sample(samples, "PREPARATION", seconds(start, execution));
                    sample(samples, "MODEL", seconds(execution, measuredEnd));
                }
            }
            if (fact.firstAutomaticPostedAt() != null
                    && publicAt != null
                    && row.availableAt().isBefore(publicAt)) {
                queue += interval(row.availableAt(), start, publicAt);
                if (execution == null) unsplit += interval(start, end, publicAt);
                else {
                    preparation += interval(start, execution, publicAt);
                    model += interval(execution, end, publicAt);
                }
            }
            if (fact.finished() && end != null) {
                finishedQueue += seconds(row.availableAt(), start);
                if (execution == null) finishedUnsplit += seconds(start, end);
                else {
                    finishedPreparation += seconds(start, execution);
                    finishedModel += seconds(execution, end);
                }
            }
        }
        Double posted = null, finishedPosted = null;
        for (var fact : timelines) {
            Instant basis = fact.origin() == ReviewOrigin.EVENT ? fact.eventAt() : fact.createdAt();
            if (basis != null) {
                sample(samples, "ASKED", seconds(basis, fact.createdAt()));
                if (fact.firstAutomaticPostedAt() != null)
                    sample(samples, "TOTAL", seconds(basis, fact.firstAutomaticPostedAt()));
                if (fact.finished() && fact.completedAt() != null)
                    sample(samples, "FINISHED", seconds(basis, fact.completedAt()));
            }
            var publicAt = fact.publicCompletedAt();
            if (publicAt != null && fact.firstAutomaticPostedAt() != null) {
                double delay = seconds(publicAt, fact.firstAutomaticPostedAt());
                sample(samples, "POSTED", delay);
                posted = (posted == null ? 0d : posted) + delay;
                if (fact.finished()) finishedPosted = (finishedPosted == null ? 0d : finishedPosted) + delay;
            }
            if (publicAt != null && fact.firstReadAt() != null)
                sample(samples, "READ", seconds(publicAt, fact.firstReadAt()));
        }
        var stages = new HashMap<String, ReviewTimingStage>();
        for (var entry : samples.entrySet()) {
            var sorted = entry.getValue().stream().sorted().toList();
            stages.put(
                    entry.getKey(), new ReviewTimingStage(sorted.size(), quantile(sorted, 0.5), quantile(sorted, 0.9)));
        }
        var facts = product.facts(workspaceId, reviewIds, from, to);
        return new ReviewTimingSnapshot(
                stages,
                publicBreakdownKnown
                        ? new ReviewTimingSums(facts.asked(), queue, preparation, model, unsplit, posted)
                        : null,
                finishedBreakdownKnown
                        ? new ReviewTimingSums(
                                facts.finishedAsked(),
                                finishedQueue,
                                finishedPreparation,
                                finishedModel,
                                finishedUnsplit,
                                finishedPosted)
                        : null,
                longWait,
                facts.finishedAfterMerge(),
                facts.finishedAfterMergeStartedByMerge(),
                facts.feedbackWithheldAsMerged());
    }

    private static void sample(Map<String, List<Double>> samples, String name, double value) {
        samples.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
    }

    private static double interval(Instant start, @Nullable Instant end, Instant cutoff) {
        if (!start.isBefore(cutoff)) return 0;
        return seconds(start, end == null || end.isAfter(cutoff) ? cutoff : end);
    }

    private static double seconds(Instant from, Instant to) {
        var elapsed = Duration.between(from, to);
        return Math.max(0, elapsed.getSeconds() + elapsed.getNano() / 1_000_000_000d);
    }

    private static double quantile(List<Double> sorted, double p) {
        double index = (sorted.size() - 1) * p;
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        return sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * (index - lower);
    }
}
