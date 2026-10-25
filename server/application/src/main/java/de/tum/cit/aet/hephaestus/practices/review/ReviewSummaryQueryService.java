package de.tum.cit.aet.hephaestus.practices.review;

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
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewScriptQuery;
import de.tum.cit.aet.hephaestus.practices.trace.ItemTraceRepository;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        return reviews.search(ws, filter, page).map(this::summary);
    }

    public ReviewRefDTO ref(Review review) {
        return ref(review, item(review));
    }

    /** Each review's reference, keyed by review id, with the reviewed work of all of them read at once. */
    public Map<UUID, ReviewRefDTO> refs(long workspaceId, Collection<Review> reviews) {
        var items = references.items(
                workspaceId, reviews.stream().map(Review::getItemId).distinct().toList());
        Map<UUID, ReviewRefDTO> refs = new LinkedHashMap<>();
        reviews.forEach(review ->
                refs.put(review.getId(), ref(review, Objects.requireNonNull(items.get(review.getItemId())))));
        return refs;
    }

    private static ReviewRefDTO ref(Review review, ItemRefDTO item) {
        return new ReviewRefDTO(
                review.getId(),
                review.getKind(),
                item,
                review.getItemVersion(),
                review.getOrigin(),
                review.getStatus(),
                review.getCreatedAt(),
                review.getCompletedAt());
    }

    public ReviewDTO view(long ws, UUID id) {
        return json.convertValue(summary(require(ws, id)), ReviewDTO.class);
    }

    public ReviewSummaryDTO summary(Review review) {
        var jobs = runtime.runtime(review.getWorkspaceId(), review.getId())
                .map(ReviewRuntimeSnapshot::jobs)
                .orElse(List.of());
        var pinned = practices(review);
        var model =
                assignments
                        .findByWorkspaceIdAndReviewIdOrderByPracticeIdAsc(review.getWorkspaceId(), review.getId())
                        .stream()
                        .map(ReviewPractice::getMethod)
                        .filter(Objects::nonNull)
                        .flatMap(m -> m.models().stream())
                        .filter(m -> m.slot().equals("review") && m.model() != null)
                        .map(m -> Objects.requireNonNull(m.model()).modelId())
                        .findFirst()
                        .orElse(null);
        return new ReviewSummaryDTO(
                review.getId(),
                review.getKind(),
                item(review),
                review.getItemVersion(),
                review.getOrigin(),
                review.getStatus(),
                review.getCreatedAt(),
                review.getCompletedAt(),
                event(review),
                review.getRequestedById() == null
                        ? null
                        : references
                                .accounts(review.getWorkspaceId(), List.of(review.getRequestedById()))
                                .get(review.getRequestedById()),
                review.getBatchId(),
                review.getOrderedAt(),
                review.getStopReason(),
                pinned,
                resultCounts(review, null),
                feedbackCounts(review.getWorkspaceId(), review.getId()),
                jobs.size(),
                (int) jobs.stream()
                        .filter(j -> j.status() == ReviewJobStatus.QUEUED || j.status() == ReviewJobStatus.RUNNING)
                        .count(),
                model);
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

    public List<PracticeScriptRunDTO> scripts(long ws, UUID id) {
        require(ws, id);
        return scripts.forReview(ws, id);
    }

    public List<ReviewPracticeDTO> practices(Review review) {
        long ws = review.getWorkspaceId();
        var practicesById = practices.findByWorkspaceId(ws).stream().collect(Collectors.toMap(Practice::getId, p -> p));
        var rows = assignments.findByWorkspaceIdAndReviewIdOrderByPracticeIdAsc(ws, review.getId());
        var versionById = versions
                .findByWorkspaceIdAndIdIn(
                        ws,
                        rows.stream()
                                .map(ReviewPractice::getPracticeVersionId)
                                .filter(Objects::nonNull)
                                .toList())
                .stream()
                .collect(Collectors.toMap(PracticeVersion::getId, v -> v));
        var scriptRows = scripts.forReview(ws, review.getId());
        var countsByPractice = members.practiceCounts(ws, review.getId()).stream()
                .collect(Collectors.toMap(ReviewItemRepository.PracticeCounts::getPracticeId, Function.identity()));
        return rows.stream()
                .map(a -> {
                    var p = Objects.requireNonNull(practicesById.get(a.getPracticeId()));
                    var v = versionById.get(a.getPracticeVersionId());
                    var stored = countsByPractice.get(a.getPracticeId());
                    var counts = stored == null
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
                            counts,
                            resultCounts(review, a.getPracticeId()),
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

    public @Nullable ItemEventDTO event(Review r) {
        var eventId = r.getEventId();
        if (eventId == null) return null;
        return events.event(r.getWorkspaceId(), eventId)
                .map(row -> new ItemEventDTO(
                        row.getId(),
                        Long.toString(row.getItemId()),
                        row.getName(),
                        row.getItemVersion(),
                        row.getOccurredAt(),
                        row.getFoundBy(),
                        row.getState(),
                        row.getReason(),
                        row.getDecidedAt(),
                        row.getReviewId()))
                .orElse(null);
    }

    private ReviewResultCountsDTO resultCounts(Review review, @Nullable Long practiceId) {
        var row = reviews.resultCounts(
                review.getWorkspaceId(), review.getId(), review.getKind() == ReviewKind.SCAN, practiceId);
        return new ReviewResultCountsDTO(
                Math.toIntExact(row.getMet()),
                Math.toIntExact(row.getNotMet()),
                Math.toIntExact(row.getDoesNotApply()),
                Math.toIntExact(row.getCouldNotDetermine()));
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

    public ReviewFeedbackCountsDTO feedbackCounts(long ws, UUID id) {
        var c = new EnumMap<FeedbackState, Integer>(FeedbackState.class);
        reviews.feedbackCounts(ws, id).forEach(row -> c.put(row.getState(), Math.toIntExact(row.getCount())));
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
