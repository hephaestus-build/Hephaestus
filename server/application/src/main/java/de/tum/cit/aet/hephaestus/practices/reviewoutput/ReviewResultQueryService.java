package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.dto.GroupRefDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.ReviewFeedbackCountsDTO;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemQueryFilter;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultMarkService;
import de.tum.cit.aet.hephaestus.practices.result.ResultPresentation;
import de.tum.cit.aet.hephaestus.practices.result.ResultQueryFilter;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultView;
import de.tum.cit.aet.hephaestus.practices.result.ResultViewQuery;
import de.tum.cit.aet.hephaestus.practices.result.dto.ProblemSummaryDTO;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewPersonDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewBoundResultDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewProblemDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewProblemDetailDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewResultDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewResultDetailDTO;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultSort;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewResultQueryService {
    private final ResultRepository results;
    private final ProblemRepository problems;
    private final ResultViewQuery views;
    private final ResultPresentation presentation;
    private final ProductReferenceQuery references;
    private final EvidenceAuthorization authorization;
    private final ResultMarkService marks;
    private final ReviewResultListRepository listRows;

    public Page<ReviewResultDTO> list(long workspaceId, ResultQueryFilter filter, ResultSort sort, Pageable page) {
        var rows = results.list(workspaceId, filter, sort, false, page);
        if (rows.isEmpty()) return new PageImpl<>(List.of(), page, rows.getTotalElements());
        var facts = rows.getContent().stream().map(Result::facts).toList();
        var permitted = authorization.permitsAll(workspaceId, facts, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW);
        var ids = rows.getContent().stream().map(Result::getId).toList();
        var marked = listRows.markTimes(workspaceId, ids).stream()
                .collect(Collectors.groupingBy(
                        ReviewResultListRepository.MarkTime::resultId,
                        Collectors.toMap(
                                ReviewResultListRepository.MarkTime::kind,
                                ReviewResultListRepository.MarkTime::createdAt)));
        var counts = listRows.problemCounts(workspaceId, ids).stream()
                .collect(Collectors.toMap(
                        ReviewResultListRepository.ProblemCount::resultId,
                        ReviewResultListRepository.ProblemCount::count));
        var permittedIds = ids.stream().filter(permitted::contains).toList();
        var summaries = permittedIds.isEmpty()
                ? Map.<UUID, List<ProblemSummaryDTO>>of()
                : listRows.problemSummaries(workspaceId, permittedIds).stream()
                        .collect(Collectors.groupingBy(
                                ReviewResultListRepository.ProblemSummary::resultId,
                                Collectors.mapping(
                                        row -> new ProblemSummaryDTO(row.id(), row.severity(), row.summary()),
                                        Collectors.toList())));
        var feedback = listRows.feedbackCounts(workspaceId, ids).stream()
                .collect(Collectors.groupingBy(ReviewResultListRepository.FeedbackCount::resultId));
        var developers = references.namedDevelopers(
                workspaceId,
                rows.getContent().stream()
                        .map(Result::getDeveloperId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
        return new PageImpl<>(
                views.operatorViews(workspaceId, facts).stream()
                        .map(v -> {
                            var r = v.result();
                            var activeMarks = marked.getOrDefault(r.id(), Map.of());
                            var freshness = presentation.freshness(v);
                            return new ReviewResultDTO(
                                    r.id(),
                                    r.reviewId(),
                                    v.item(),
                                    v.practice().getSlug(),
                                    presentation.name(v),
                                    r.outcome(),
                                    r.severity(),
                                    permitted.contains(r.id()) ? r.summary() : null,
                                    v.review().getOrigin(),
                                    r.recordedAt(),
                                    freshness.currentness(),
                                    freshness.unverifiableReason(),
                                    freshness.capturedAt(),
                                    activeMarks.get(ResultMarkKind.INCORRECT),
                                    activeMarks.get(ResultMarkKind.ACCEPTED),
                                    summaries.getOrDefault(r.id(), List.of()),
                                    r.developerId() == null ? null : developers.get(r.developerId()),
                                    Math.toIntExact(counts.getOrDefault(r.id(), 0L)),
                                    feedbackCounts(feedback.getOrDefault(r.id(), List.of())));
                        })
                        .toList(),
                page,
                rows.getTotalElements());
    }

    private static ReviewFeedbackCountsDTO feedbackCounts(List<ReviewResultListRepository.FeedbackCount> rows) {
        var counts = new EnumMap<FeedbackState, Integer>(FeedbackState.class);
        rows.forEach(row -> counts.put(row.state(), Math.toIntExact(row.count())));
        return ReviewFeedbackCountsDTO.from(counts);
    }

    public ReviewResultDetailDTO get(long ws, UUID id) {
        var r = require(ws, id);
        var v = views.operatorViews(ws, List.of(r.facts())).getFirst();
        boolean permitted = authorization.permits(ws, r, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW);
        var freshness = presentation.freshness(v);
        return new ReviewResultDetailDTO(
                r.getId(),
                r.getReviewId(),
                v.item(),
                v.practice().getSlug(),
                presentation.name(v),
                r.getOutcome(),
                r.getSeverity(),
                permitted ? r.getSummary() : null,
                v.review().getOrigin(),
                r.getRecordedAt(),
                freshness.currentness(),
                freshness.unverifiableReason(),
                freshness.capturedAt(),
                presentation.markedAt(r, ResultMarkKind.INCORRECT),
                presentation.markedAt(r, ResultMarkKind.ACCEPTED),
                r.getPracticeVersionId() == null
                        ? null
                        : r.getPracticeVersionId().toString(),
                v.version() == null ? null : v.version().getNumber(),
                r.getItemVersion(),
                permitted ? r.getExplanation() : null,
                presentation.evidence(r, permitted),
                presentation.problems(r).stream()
                        .map(p -> presentation.problem(p, permitted))
                        .toList(),
                presentation.example(r, permitted),
                presentation.coverage(r, permitted),
                r.getEndedAt(),
                r.getEndReason(),
                developer(v),
                r.getEvidenceVersion(),
                !permitted,
                marks.list(ws, id).stream().map(m -> presentation.mark(ws, m)).toList(),
                presentation.disagreements(r),
                presentation.boundFeedback(r));
    }

    public Page<ReviewProblemDTO> listProblems(
            long workspaceId, ResultQueryFilter filter, ProblemQueryFilter child, Pageable page) {
        var rows = results.problems(workspaceId, filter, child, page);
        var parents = parents(workspaceId, rows.getContent());
        return rows.map(p -> problemRow(p, Objects.requireNonNull(parents.get(p.getResultId()))));
    }

    public ReviewProblemDetailDTO problem(long ws, UUID id) {
        var p = problems.findByIdAndWorkspaceId(id, ws)
                .orElseThrow(() -> new EntityNotFoundException("Problem", id.toString()));
        var parent = Objects.requireNonNull(parents(ws, List.of(p)).get(p.getResultId()));
        var row = problemRow(p, parent);
        var v = parent.view();
        return new ReviewProblemDetailDTO(
                row.id(),
                row.resultId(),
                row.reviewId(),
                row.item(),
                row.practiceSlug(),
                row.practiceName(),
                row.group(),
                row.person(),
                row.severity(),
                row.summary(),
                row.path(),
                row.startLine(),
                row.endLine(),
                row.side(),
                row.sourceKind(),
                row.evidenceHidden(),
                row.currentness(),
                row.unverifiableReason(),
                row.capturedAt(),
                row.markedIncorrectAt(),
                row.acceptedAt(),
                row.disagreedAt(),
                row.recordedAt(),
                presentation.problem(p, !row.evidenceHidden()),
                bound(v, !row.evidenceHidden()));
    }

    public ReviewBoundResultDTO bound(ResultView v, boolean permitted) {
        var r = v.result();
        var freshness = presentation.freshness(v);
        return new ReviewBoundResultDTO(
                r.id(),
                r.reviewId(),
                v.practice().getSlug(),
                presentation.name(v),
                r.outcome(),
                r.severity(),
                permitted ? r.summary() : null,
                v.item(),
                developer(v),
                r.recordedAt(),
                freshness.currentness(),
                freshness.unverifiableReason(),
                freshness.capturedAt());
    }

    /** What a problem row shows of its result, read once for every problem of the page that shares the result. */
    private record Parent(
            Result result,
            ResultView view,
            boolean permitted,
            Map<ResultMarkKind, Instant> marks,
            @Nullable ReviewPersonDTO developer,
            @Nullable Instant disagreedAt) {}

    /**
     * The results of {@code rows}, each with its view, its evidence permission, its active marks, its developer and its
     * latest disagreement, read for all of them at once and keyed by result id.
     */
    private Map<UUID, Parent> parents(long ws, List<Problem> rows) {
        if (rows.isEmpty()) return Map.of();
        var ids = rows.stream().map(Problem::getResultId).distinct().toList();
        var byId = results.findByWorkspaceIdAndIdIn(ws, ids).stream()
                .collect(Collectors.toMap(Result::getId, Function.identity()));
        var loaded = ids.stream()
                .map(id -> Objects.requireNonNull(
                        byId.get(id), () -> "Result " + id + " of a problem is not in the workspace"))
                .toList();
        var facts = loaded.stream().map(Result::facts).toList();
        var permitted = authorization.permitsAll(ws, facts, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW);
        var viewById = views.operatorViews(ws, facts).stream()
                .collect(Collectors.toMap(v -> v.result().id(), Function.identity()));
        Map<UUID, Map<ResultMarkKind, Instant>> marked = new HashMap<>();
        listRows.markTimes(ws, ids)
                .forEach(mark -> marked.computeIfAbsent(mark.resultId(), ignored -> new EnumMap<>(ResultMarkKind.class))
                        .merge(mark.kind(), mark.createdAt(), (left, right) -> left.isAfter(right) ? left : right));
        var developers = references.namedDevelopers(
                ws,
                loaded.stream()
                        .map(Result::getDeveloperId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
        var disagreed = presentation.latestDisagreements(ws, ids);
        Map<UUID, Parent> parents = new HashMap<>();
        for (var r : loaded) {
            var developer = r.getDeveloperId() == null ? null : developers.get(r.getDeveloperId());
            parents.put(
                    r.getId(),
                    new Parent(
                            r,
                            Objects.requireNonNull(viewById.get(r.getId())),
                            permitted.contains(r.getId()),
                            marked.getOrDefault(r.getId(), Map.of()),
                            developer,
                            disagreed.get(r.getId())));
        }
        return parents;
    }

    private ReviewProblemDTO problemRow(Problem p, Parent parent) {
        var r = parent.result();
        var v = parent.view();
        boolean permitted = parent.permitted();
        var dto = presentation.problem(p, permitted);
        var primary = dto.citations().isEmpty() ? null : dto.citations().getFirst();
        var group = v.practice().getGroup();
        var freshness = presentation.freshness(v);
        return new ReviewProblemDTO(
                p.getId(),
                r.getId(),
                r.getReviewId(),
                v.item(),
                v.practice().getSlug(),
                presentation.name(v),
                group == null
                        ? null
                        : new GroupRefDTO(group.getSlug(), group.getName(), group.getIcon(), group.getColor()),
                parent.developer(),
                p.getSeverity(),
                dto.summary(),
                primary == null ? null : primary.path(),
                primary == null ? null : primary.startLine(),
                primary == null ? null : primary.endLine(),
                primary == null ? null : primary.side(),
                primary == null ? null : primary.sourceKind(),
                !permitted,
                freshness.currentness(),
                freshness.unverifiableReason(),
                freshness.capturedAt(),
                parent.marks().get(ResultMarkKind.INCORRECT),
                parent.marks().get(ResultMarkKind.ACCEPTED),
                parent.disagreedAt(),
                r.getRecordedAt());
    }

    private Result require(long ws, UUID id) {
        return results.findByIdAndWorkspaceId(id, ws)
                .orElseThrow(() -> new EntityNotFoundException("Result", id.toString()));
    }

    private @Nullable ReviewPersonDTO developer(ResultView v) {
        var r = v.result();
        return r.developerId() == null
                ? null
                : references
                        .namedDevelopers(r.workspaceId(), List.of(r.developerId()))
                        .get(r.developerId());
    }
}
