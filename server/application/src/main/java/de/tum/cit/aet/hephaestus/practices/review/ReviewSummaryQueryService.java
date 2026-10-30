package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.auth.dto.AccountRefDTO;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.ReviewFeedbackCountsDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.dto.ItemEventDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewDetailDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewItemCountsDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewJobDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewPracticeDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewRefDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewResultCountsDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewRuntimeDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewStageTimingDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewSummaryDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewUsageAttemptDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewUsageDTO;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewPractice;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeScriptRunDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewScriptQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewShownStatus;
import de.tum.cit.aet.hephaestus.practices.trace.ItemTraceRepository;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewSummaryQueryService {
    private final ReviewRepository reviews;
    private final ReviewPracticeRepository assignments;
    private final PracticeRepository practices;
    private final PracticeVersionRepository versions;
    private final ProductReferenceQuery references;
    private final ReviewRuntimeQuery runtime;
    private final ReviewScriptQuery scripts;
    private final ReviewItemRepository members;
    private final ItemTraceRepository events;
    private final JsonMapper json;

    public Review require(long ws, UUID id) {
        return reviews.findByIdAndWorkspaceId(id, ws)
                .orElseThrow(() -> new EntityNotFoundException("Review", id.toString()));
    }

    public Page<ReviewSummaryDTO> list(long ws, ReviewFilterParams filter, Pageable page) {
        var found = reviews.search(ws, filter, page);
        var summaries = summaries(ws, found.getContent());
        return found.map(review -> Objects.requireNonNull(summaries.get(review.getId())));
    }

    public ReviewRefDTO ref(Review review) {
        var jobs = runtime.jobCounts(review.getWorkspaceId(), List.of(review.getId()));
        return ref(review, item(review), jobs.getOrDefault(review.getId(), Map.of()));
    }

    /** Each review's reference, keyed by review id, with the reviewed work of all of them read at once. */
    public Map<UUID, ReviewRefDTO> refs(long workspaceId, Collection<Review> reviews) {
        var items = references.items(
                workspaceId, reviews.stream().map(Review::getItemId).distinct().toList());
        var jobs = runtime.jobCounts(
                workspaceId, reviews.stream().map(Review::getId).toList());
        Map<UUID, ReviewRefDTO> refs = new LinkedHashMap<>();
        reviews.forEach(review -> refs.put(
                review.getId(),
                ref(
                        review,
                        Objects.requireNonNull(items.get(review.getItemId())),
                        jobs.getOrDefault(review.getId(), Map.of()))));
        return refs;
    }

    private static ReviewRefDTO ref(Review review, ItemRefDTO item, Map<ReviewJobStatus, Long> jobs) {
        return new ReviewRefDTO(
                review.getId(),
                review.getKind(),
                item,
                review.getItemVersion(),
                review.getOrigin(),
                ReviewShownStatus.of(review.getStatus(), jobs),
                review.getCreatedAt(),
                review.getCompletedAt());
    }

    public ReviewDTO view(long ws, UUID id) {
        return json.convertValue(summary(require(ws, id)), ReviewDTO.class);
    }

    public ReviewSummaryDTO summary(Review review) {
        return Objects.requireNonNull(
                summaries(review.getWorkspaceId(), List.of(review)).get(review.getId()));
    }

    /**
     * Each review's summary, keyed by review id, read in a fixed number of statements per integration however many
     * reviews and practices the page holds.
     */
    public Map<UUID, ReviewSummaryDTO> summaries(long ws, List<Review> page) {
        if (page.isEmpty()) return Map.of();
        var reads = new SummaryReads(ws, page);
        Map<UUID, ReviewSummaryDTO> summaries = new LinkedHashMap<>();
        for (var review : page) {
            var jobs = reads.jobCounts.getOrDefault(review.getId(), Map.of());
            var rows = reads.assignmentsByReview.getOrDefault(review.getId(), List.of());
            var model = rows.stream()
                    .map(ReviewPractice::getMethod)
                    .filter(Objects::nonNull)
                    .flatMap(m -> m.models().stream())
                    .filter(m -> m.slot().equals("review") && m.model() != null)
                    .map(m -> Objects.requireNonNull(m.model()).modelId())
                    .findFirst()
                    .orElse(null);
            var eventId = review.getEventId();
            var event = eventId == null ? null : reads.eventById.get(eventId);
            var requester = review.getRequestedById();
            summaries.put(
                    review.getId(),
                    new ReviewSummaryDTO(
                            review.getId(),
                            review.getKind(),
                            Objects.requireNonNull(reads.items.get(review.getItemId())),
                            review.getItemVersion(),
                            review.getOrigin(),
                            ReviewShownStatus.of(review.getStatus(), jobs),
                            review.getCreatedAt(),
                            review.getCompletedAt(),
                            event,
                            requester == null ? null : reads.requesters.get(requester),
                            review.getBatchId(),
                            review.getOrderedAt(),
                            review.getStopReason(),
                            practices(review, rows, reads),
                            reads.resultCounts(review.getId(), null),
                            feedbackCounts(reads.feedback.getOrDefault(review.getId(), Map.of())),
                            Math.toIntExact(jobs.values().stream()
                                    .mapToLong(Long::longValue)
                                    .sum()),
                            Math.toIntExact(jobs.getOrDefault(ReviewJobStatus.QUEUED, 0L)
                                    + jobs.getOrDefault(ReviewJobStatus.RUNNING, 0L)),
                            model));
        }
        return summaries;
    }

    /** Everything a page of summaries reads, each kind of fact in one query for all of its reviews. */
    private final class SummaryReads {
        final Map<UUID, Map<ReviewJobStatus, Long>> jobCounts;
        final Map<UUID, List<ReviewPractice>> assignmentsByReview;
        final Map<Long, Practice> practicesById;
        final Map<Long, PracticeVersion> versionById;
        final Map<UUID, List<PracticeScriptRunDTO>> scriptsByReview;
        final Map<UUID, Map<Long, ReviewItemRepository.PracticeCounts>> memberCounts = new HashMap<>();
        final Map<UUID, Map<Long, ReviewRepository.ResultCounts>> results = new HashMap<>();
        final Map<UUID, Map<FeedbackState, Integer>> feedback = new HashMap<>();
        final Map<Long, ItemRefDTO> items;
        final Map<UUID, ItemEventDTO> eventById;
        final Map<Long, AccountRefDTO> requesters;

        SummaryReads(long ws, List<Review> page) {
            var ids = page.stream().map(Review::getId).toList();
            jobCounts = runtime.jobCounts(ws, ids);
            assignmentsByReview =
                    assignments.findByWorkspaceIdAndReviewIdInOrderByReviewIdAscPracticeIdAsc(ws, ids).stream()
                            .collect(Collectors.groupingBy(ReviewPractice::getReviewId));
            var practiceIds = assignmentsByReview.values().stream()
                    .flatMap(List::stream)
                    .map(ReviewPractice::getPracticeId)
                    .distinct()
                    .toList();
            practicesById = practiceIds.isEmpty()
                    ? Map.of()
                    : practices.findByWorkspaceIdAndIdIn(ws, practiceIds).stream()
                            .collect(Collectors.toMap(Practice::getId, Function.identity()));
            var versionIds = assignmentsByReview.values().stream()
                    .flatMap(List::stream)
                    .map(ReviewPractice::getPracticeVersionId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            versionById = versionIds.isEmpty()
                    ? Map.of()
                    : versions.findByWorkspaceIdAndIdIn(ws, versionIds).stream()
                            .collect(Collectors.toMap(PracticeVersion::getId, Function.identity()));
            scriptsByReview = scripts.forReviews(ws, ids);
            members.practiceCounts(ws, ids)
                    .forEach(row -> memberCounts
                            .computeIfAbsent(row.getReviewId(), ignored -> new HashMap<>())
                            .put(row.getPracticeId(), row));
            var scans = page.stream()
                    .filter(review -> review.getKind() == ReviewKind.SCAN)
                    .map(Review::getId)
                    .toList();
            var itemReviews = page.stream()
                    .filter(review -> review.getKind() != ReviewKind.SCAN)
                    .map(Review::getId)
                    .toList();
            if (!itemReviews.isEmpty())
                reviews.itemResultCounts(ws, itemReviews).forEach(this::addResults);
            if (!scans.isEmpty()) reviews.scanResultCounts(ws, scans).forEach(this::addResults);
            reviews.feedbackCounts(ws, ids)
                    .forEach(row -> feedback.computeIfAbsent(
                                    row.getReviewId(), ignored -> new EnumMap<>(FeedbackState.class))
                            .put(row.getState(), Math.toIntExact(row.getCount())));
            items = references.items(
                    ws, page.stream().map(Review::getItemId).distinct().toList());
            var eventIds = page.stream()
                    .map(Review::getEventId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            eventById = eventIds.isEmpty()
                    ? Map.of()
                    : events.events(ws, eventIds).stream()
                            .collect(Collectors.toMap(
                                    ItemTraceRepository.EventRow::getId, ReviewSummaryQueryService::eventOf));
            var requesterIds = page.stream()
                    .map(Review::getRequestedById)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            requesters = requesterIds.isEmpty() ? Map.of() : references.accounts(ws, requesterIds);
        }

        private void addResults(ReviewRepository.PracticeResultCounts row) {
            results.computeIfAbsent(row.getReviewId(), ignored -> new HashMap<>())
                    .put(row.getPracticeId(), row);
        }

        /** The review's result counts, of one practice or, with {@code practiceId} null, of all of them. */
        ReviewResultCountsDTO resultCounts(UUID reviewId, @Nullable Long practiceId) {
            var byPractice = results.getOrDefault(reviewId, Map.of());
            var rows = practiceId == null
                    ? byPractice.values()
                    : Optional.ofNullable(byPractice.get(practiceId)).stream().toList();
            return new ReviewResultCountsDTO(
                    Math.toIntExact(rows.stream()
                            .mapToLong(ReviewRepository.ResultCounts::getMet)
                            .sum()),
                    Math.toIntExact(rows.stream()
                            .mapToLong(ReviewRepository.ResultCounts::getNotMet)
                            .sum()),
                    Math.toIntExact(rows.stream()
                            .mapToLong(ReviewRepository.ResultCounts::getDoesNotApply)
                            .sum()),
                    Math.toIntExact(rows.stream()
                            .mapToLong(ReviewRepository.ResultCounts::getCouldNotDetermine)
                            .sum()));
        }
    }

    private static ItemEventDTO eventOf(ItemTraceRepository.EventRow row) {
        return new ItemEventDTO(
                row.getId(),
                Long.toString(row.getItemId()),
                row.getName(),
                row.getItemVersion(),
                row.getOccurredAt(),
                row.getFoundBy(),
                row.getState(),
                row.getReason(),
                row.getDecidedAt(),
                row.getReviewId());
    }

    public ReviewDetailDTO detail(long ws, UUID id) {
        var r = require(ws, id);
        var s = summary(r);
        var snapshot = runtime.runtime(ws, id).orElse(null);
        var runtimeDTO = snapshot == null
                ? null
                : new ReviewRuntimeDTO(snapshot.jobs().stream()
                        .map(j -> new ReviewJobDTO(
                                j.id(),
                                j.status(),
                                j.attempt(),
                                j.processingStatus(),
                                j.availableAt(),
                                j.startedAt(),
                                j.completedAt(),
                                j.exitCode(),
                                j.errorMessage(),
                                j.traceId()))
                        .toList());
        var times = snapshot == null
                ? List.<ReviewStageTimingDTO>of()
                : snapshot.jobs().stream()
                        .map(j -> new ReviewStageTimingDTO(
                                "RUN",
                                j.startedAt(),
                                j.completedAt(),
                                j.startedAt() == null || j.completedAt() == null
                                        ? null
                                        : Math.toIntExact(Duration.between(j.startedAt(), j.completedAt())
                                                .toMillis())))
                        .toList();
        return new ReviewDetailDTO(
                s.id(),
                s.kind(),
                s.item(),
                s.itemVersion(),
                s.origin(),
                s.status(),
                s.createdAt(),
                s.completedAt(),
                s.startedBy(),
                s.requestedBy(),
                s.batchId(),
                s.orderedAt(),
                s.stopReason(),
                s.practices(),
                s.results(),
                s.feedback(),
                s.jobCount(),
                s.activeJobCount(),
                s.model(),
                runtimeDTO,
                usage(ws, id),
                times,
                null);
    }

    public ReviewUsageDTO usage(long ws, UUID id) {
        require(ws, id);
        var u = runtime.usage(ws, id);
        return new ReviewUsageDTO(
                u.costUsd() == null ? null : u.costUsd().toPlainString(),
                u.priced(),
                u.calls(),
                u.inputTokens(),
                u.outputTokens(),
                u.attempts().stream()
                        .map(a -> new ReviewUsageAttemptDTO(
                                a.jobId(),
                                a.attempt(),
                                a.calls(),
                                a.inputTokens(),
                                a.outputTokens(),
                                a.reasoningTokens(),
                                a.cacheReadTokens(),
                                a.cacheWriteTokens(),
                                a.costUsd() == null ? null : a.costUsd().toPlainString(),
                                a.pricingState()))
                        .toList());
    }

    private static List<ReviewPracticeDTO> practices(Review review, List<ReviewPractice> rows, SummaryReads reads) {
        var counts = reads.memberCounts.getOrDefault(review.getId(), Map.of());
        var scriptRows = reads.scriptsByReview.getOrDefault(review.getId(), List.of());
        return rows.stream()
                .map(a -> {
                    var p = Objects.requireNonNull(reads.practicesById.get(a.getPracticeId()));
                    var v = a.getPracticeVersionId() == null ? null : reads.versionById.get(a.getPracticeVersionId());
                    var stored = counts.get(a.getPracticeId());
                    var itemCounts = stored == null
                            ? new ReviewItemCountsDTO(0, 0, 0, 0, 0)
                            : new ReviewItemCountsDTO(
                                    Math.toIntExact(stored.getListed()),
                                    Math.toIntExact(stored.getReviewed()),
                                    Math.toIntExact(stored.getReused()),
                                    Math.toIntExact(stored.getWithheld()),
                                    review.getStatus().isTerminal() ? Math.toIntExact(stored.getPending()) : 0);
                    return new ReviewPracticeDTO(
                            p.getSlug(),
                            v == null || v.getName() == null ? "Unknown practice version" : v.getName(),
                            a.getPracticeVersionId() == null
                                    ? null
                                    : a.getPracticeVersionId().toString(),
                            v == null ? null : v.getNumber(),
                            a.getStatus(),
                            a.getReason(),
                            itemCounts,
                            reads.resultCounts(review.getId(), a.getPracticeId()),
                            scriptRows.stream()
                                    .filter(run ->
                                            run.practiceId().equals(p.getId().toString()))
                                    .max(Comparator.comparingInt(PracticeScriptRunDTO::attempt))
                                    .orElse(null));
                })
                .toList();
    }

    public ItemRefDTO item(Review r) {
        return Objects.requireNonNull(
                references.items(r.getWorkspaceId(), List.of(r.getItemId())).get(r.getItemId()));
    }

    public static ReviewResultCountsDTO counts(Collection<Result> rows) {
        var c = new EnumMap<ResultOutcome, Integer>(ResultOutcome.class);
        rows.forEach(r -> c.merge(r.getOutcome(), 1, Integer::sum));
        return new ReviewResultCountsDTO(
                c.getOrDefault(ResultOutcome.MET, 0),
                c.getOrDefault(ResultOutcome.NOT_MET, 0),
                c.getOrDefault(ResultOutcome.DOES_NOT_APPLY, 0),
                c.getOrDefault(ResultOutcome.COULD_NOT_DETERMINE, 0));
    }

    private static ReviewFeedbackCountsDTO feedbackCounts(Map<FeedbackState, Integer> c) {
        return new ReviewFeedbackCountsDTO(
                c.getOrDefault(FeedbackState.AWAITING_APPROVAL, 0),
                c.getOrDefault(FeedbackState.READY, 0),
                c.getOrDefault(FeedbackState.DELIVERED, 0),
                c.getOrDefault(FeedbackState.UNCONFIRMED, 0),
                c.getOrDefault(FeedbackState.WITHHELD, 0),
                c.getOrDefault(FeedbackState.REJECTED, 0),
                c.getOrDefault(FeedbackState.REPLACED, 0),
                c.getOrDefault(FeedbackState.FAILED, 0));
    }
}
