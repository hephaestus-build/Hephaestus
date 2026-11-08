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
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewDeveloperDTO;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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

    public Page<ReviewResultDTO> list(long ws, ResultQueryFilter filter, ResultSort sort, Pageable page) {
        var rows = results.list(ws, filter, sort, false, page);
        if (rows.isEmpty()) return new PageImpl<>(List.of(), page, rows.getTotalElements());
        var permitted = authorization.permitsAll(ws, rows.getContent(), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW);
        var ids = rows.getContent().stream().map(Result::getId).toList();
        var marked = listRows.markTimes(ws, ids).stream()
                .collect(Collectors.groupingBy(
                        ReviewResultListRepository.MarkTime::resultId,
                        Collectors.toMap(
                                ReviewResultListRepository.MarkTime::kind,
                                ReviewResultListRepository.MarkTime::createdAt)));
        var counts = listRows.problemCounts(ws, ids).stream()
                .collect(Collectors.toMap(
                        ReviewResultListRepository.ProblemCount::resultId,
                        ReviewResultListRepository.ProblemCount::count));
        var permittedIds = ids.stream().filter(permitted::contains).toList();
        var summaries = permittedIds.isEmpty()
                ? Map.<UUID, List<ProblemSummaryDTO>>of()
                : listRows.problemSummaries(ws, permittedIds).stream()
                        .collect(Collectors.groupingBy(
                                ReviewResultListRepository.ProblemSummary::resultId,
                                Collectors.mapping(
                                        row -> new ProblemSummaryDTO(row.id(), row.severity(), row.summary()),
                                        Collectors.toList())));
        var feedback = listRows.feedbackCounts(ws, ids).stream()
                .collect(Collectors.groupingBy(ReviewResultListRepository.FeedbackCount::resultId));
        var developers = references.developers(
                ws,
                rows.getContent().stream()
                        .map(Result::getDeveloperId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
        return new PageImpl<>(
                views.views(ws, rows.getContent()).stream()
                        .map(v -> {
                            var r = v.result();
                            var activeMarks = marked.getOrDefault(r.getId(), Map.of());
                            return new ReviewResultDTO(
                                    r.getId(),
                                    r.getReviewId(),
                                    v.item(),
                                    v.practice().getSlug(),
                                    presentation.name(v),
                                    r.getOutcome(),
                                    r.getSeverity(),
                                    permitted.contains(r.getId()) ? r.getSummary() : null,
                                    v.review().getOrigin(),
                                    r.getRecordedAt(),
                                    presentation.currentness(v),
                                    activeMarks.get(ResultMarkKind.INCORRECT),
                                    activeMarks.get(ResultMarkKind.ACCEPTED),
                                    summaries.getOrDefault(r.getId(), List.of()),
                                    r.getDeveloperId() == null ? null : developers.get(r.getDeveloperId()),
                                    Math.toIntExact(counts.getOrDefault(r.getId(), 0L)),
                                    feedbackCounts(feedback.getOrDefault(r.getId(), List.of())));
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
        var v = views.views(ws, List.of(r)).getFirst();
        boolean permitted = authorization.permits(ws, r, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW);
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
                presentation.currentness(v),
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
            long ws, ResultQueryFilter filter, ProblemQueryFilter child, Pageable page) {
        var rows = results.problems(ws, filter, child, page);
        return rows.map(p -> problemRow(ws, p));
    }

    public ReviewProblemDetailDTO problem(long ws, UUID id) {
        var p = problems.findByIdAndWorkspaceId(id, ws)
                .orElseThrow(() -> new EntityNotFoundException("Problem", id.toString()));
        var row = problemRow(ws, p);
        var r = require(ws, p.getResultId());
        var v = views.views(ws, List.of(r)).getFirst();
        return new ReviewProblemDetailDTO(
                row.id(),
                row.resultId(),
                row.reviewId(),
                row.item(),
                row.practiceSlug(),
                row.practiceName(),
                row.group(),
                row.developer(),
                row.severity(),
                row.summary(),
                row.path(),
                row.startLine(),
                row.endLine(),
                row.side(),
                row.sourceKind(),
                row.evidenceHidden(),
                row.currentness(),
                row.markedIncorrectAt(),
                row.acceptedAt(),
                row.disagreedAt(),
                row.recordedAt(),
                presentation.problem(p, !row.evidenceHidden()),
                bound(v, !row.evidenceHidden()));
    }

    public ReviewBoundResultDTO bound(ResultView v, boolean permitted) {
        var r = v.result();
        return new ReviewBoundResultDTO(
                r.getId(),
                r.getReviewId(),
                v.practice().getSlug(),
                presentation.name(v),
                r.getOutcome(),
                r.getSeverity(),
                permitted ? r.getSummary() : null,
                v.item(),
                developer(v),
                r.getRecordedAt(),
                presentation.currentness(v));
    }

    private ReviewProblemDTO problemRow(long ws, Problem p) {
        var r = require(ws, p.getResultId());
        var v = views.views(ws, List.of(r)).getFirst();
        boolean permitted = authorization.permits(ws, r, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW);
        var dto = presentation.problem(p, permitted);
        var primary = dto.citations().isEmpty() ? null : dto.citations().getFirst();
        var group = v.practice().getGroup();
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
                developer(v),
                p.getSeverity(),
                dto.summary(),
                primary == null ? null : primary.path(),
                primary == null ? null : primary.startLine(),
                primary == null ? null : primary.endLine(),
                primary == null ? null : primary.side(),
                primary == null ? null : primary.sourceKind(),
                !permitted,
                presentation.currentness(v),
                presentation.markedAt(r, ResultMarkKind.INCORRECT),
                presentation.markedAt(r, ResultMarkKind.ACCEPTED),
                presentation.disagreements(r).stream()
                        .map(d -> d.respondedAt())
                        .max(Instant::compareTo)
                        .orElse(null),
                r.getRecordedAt());
    }

    private Result require(long ws, UUID id) {
        return results.findByIdAndWorkspaceId(id, ws)
                .orElseThrow(() -> new EntityNotFoundException("Result", id.toString()));
    }

    private @Nullable ReviewDeveloperDTO developer(ResultView v) {
        var r = v.result();
        return r.getDeveloperId() == null
                ? null
                : references
                        .developers(r.getWorkspaceId(), List.of(r.getDeveloperId()))
                        .get(r.getDeveloperId());
    }
}
