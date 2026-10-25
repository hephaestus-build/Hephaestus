package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackVisibilityService;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackDisagreementDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.OwnFeedbackCopyRefDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.OwnFeedbackRefDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.ReviewBoundFeedbackDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.ReviewFeedbackCountsDTO;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.model.ResultMark;
import de.tum.cit.aet.hephaestus.practices.result.dto.EvidenceCitationDTO;
import de.tum.cit.aet.hephaestus.practices.result.dto.ProblemDTO;
import de.tum.cit.aet.hephaestus.practices.result.dto.ProblemSummaryDTO;
import de.tum.cit.aet.hephaestus.practices.result.dto.ResultDetailDTO;
import de.tum.cit.aet.hephaestus.practices.result.dto.ResultEvidenceDTO;
import de.tum.cit.aet.hephaestus.practices.result.dto.ResultExampleDTO;
import de.tum.cit.aet.hephaestus.practices.result.dto.ResultListDTO;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ResultMarkDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ClaimBasis;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class ResultPresentation {
    private final ResultMarkRepository marks;
    private final ProblemRepository problems;
    private final ProductReferenceQuery references;
    private final ResultCommunicationRepository communication;
    private final FeedbackVisibilityService visibility;
    private final JsonMapper json;

    public String name(ResultView view) {
        return view.version() == null || view.version().getName() == null
                ? "Unknown practice version"
                : Objects.requireNonNull(view.version().getName());
    }

    public Currentness currentness(ResultView view) {
        return ResultVisibilityPolicy.currentness(
                view.result(), view.version(), view.practice().getCurrentVersion());
    }

    public @Nullable Instant markedAt(Result result, ResultMarkKind kind) {
        return marks
                .findByWorkspaceIdAndResultIdOrderByCreatedAtDescIdDesc(result.getWorkspaceId(), result.getId())
                .stream()
                .filter(m -> m.getKind() == kind && m.getRemovedAt() == null)
                .map(ResultMark::getCreatedAt)
                .findFirst()
                .orElse(null);
    }

    public List<Problem> problems(Result result) {
        return problems.findByWorkspaceIdAndResultIdOrderByPositionAsc(result.getWorkspaceId(), result.getId());
    }

    public ProblemDTO problem(Problem child, boolean permitted) {
        return new ProblemDTO(
                child.getId(),
                child.getSeverity(),
                permitted ? child.getSummary() : "Evidence hidden",
                child.getResultId(),
                child.getPosition(),
                permitted ? child.getKey() : null,
                permitted ? child.getExplanation() : null,
                permitted
                        ? json.convertValue(child.getCitations(), new TypeReference<List<EvidenceCitationDTO>>() {})
                        : List.of(),
                permitted && child.getBasis() != null ? json.convertValue(child.getBasis(), ClaimBasis.class) : null,
                permitted ? child.getNextStep() : null);
    }

    public List<ProblemSummaryDTO> summaries(Result result, boolean permitted) {
        return permitted
                ? problems(result).stream()
                        .map(p -> new ProblemSummaryDTO(p.getId(), p.getSeverity(), p.getSummary()))
                        .toList()
                : List.of();
    }

    public List<ResultListDTO> lists(long workspaceId, Collection<ResultView> views, Set<UUID> permitted) {
        if (views.isEmpty()) return List.of();
        var ids = views.stream().map(v -> v.result().getId()).toList();
        var marked = marks.findByWorkspaceIdAndResultIdInAndRemovedAtIsNull(workspaceId, ids).stream()
                .collect(Collectors.groupingBy(ResultMark::getResultId));
        var children = problems.findByWorkspaceIdAndResultIdInOrderByResultIdAscPositionAsc(workspaceId, ids).stream()
                .collect(Collectors.groupingBy(Problem::getResultId));
        return views.stream()
                .map(v -> list(
                        v,
                        permitted.contains(v.result().getId()),
                        marked.getOrDefault(v.result().getId(), List.of()),
                        children.getOrDefault(v.result().getId(), List.of())))
                .toList();
    }

    private ResultListDTO list(ResultView v, boolean permitted, List<ResultMark> activeMarks, List<Problem> children) {
        var r = v.result();
        return new ResultListDTO(
                r.getId(),
                r.getReviewId(),
                v.item(),
                v.practice().getSlug(),
                name(v),
                r.getOutcome(),
                r.getSeverity(),
                permitted ? r.getSummary() : null,
                v.review().getOrigin(),
                r.getRecordedAt(),
                currentness(v),
                markTime(activeMarks, ResultMarkKind.INCORRECT),
                markTime(activeMarks, ResultMarkKind.ACCEPTED),
                permitted
                        ? children.stream()
                                .map(p -> new ProblemSummaryDTO(p.getId(), p.getSeverity(), p.getSummary()))
                                .toList()
                        : List.of());
    }

    private static @Nullable Instant markTime(List<ResultMark> active, ResultMarkKind kind) {
        return active.stream()
                .filter(m -> m.getKind() == kind)
                .map(ResultMark::getCreatedAt)
                .findFirst()
                .orElse(null);
    }

    public ResultDetailDTO detail(ResultView v, boolean permitted) {
        var r = v.result();
        return new ResultDetailDTO(
                r.getId(),
                r.getReviewId(),
                v.item(),
                v.practice().getSlug(),
                name(v),
                r.getOutcome(),
                r.getSeverity(),
                permitted ? r.getSummary() : null,
                v.review().getOrigin(),
                r.getRecordedAt(),
                currentness(v),
                markedAt(r, ResultMarkKind.INCORRECT),
                markedAt(r, ResultMarkKind.ACCEPTED),
                r.getPracticeVersionId() == null
                        ? null
                        : r.getPracticeVersionId().toString(),
                v.version() == null ? null : v.version().getNumber(),
                r.getItemVersion(),
                permitted ? r.getExplanation() : null,
                evidence(r, permitted),
                problems(r).stream().map(p -> problem(p, permitted)).toList(),
                example(r, permitted),
                coverage(r, permitted),
                r.getEndedAt(),
                r.getEndReason(),
                ownFeedback(r.getWorkspaceId(), List.of(r)).getOrDefault(r.getId(), List.of()));
    }

    public @Nullable ResultEvidenceDTO evidence(Result r, boolean permitted) {
        return permitted && r.getEvidence() != null
                ? json.convertValue(r.getEvidence(), ResultEvidenceDTO.class)
                : null;
    }

    public @Nullable ResultExampleDTO example(Result r, boolean permitted) {
        return permitted && r.getExample() != null ? json.convertValue(r.getExample(), ResultExampleDTO.class) : null;
    }

    public @Nullable ResultCoverage coverage(Result r, boolean permitted) {
        return permitted && r.getCoverage() != null ? json.convertValue(r.getCoverage(), ResultCoverage.class) : null;
    }

    public ResultMarkDTO mark(long ws, ResultMark m) {
        var ids = Stream.of(m.getCreatedById(), m.getRemovedById())
                .filter(Objects::nonNull)
                .toList();
        var accounts = references.accounts(ws, ids);
        var createdBy = m.getCreatedById();
        var removedBy = m.getRemovedById();
        return new ResultMarkDTO(
                m.getId(),
                m.getResultId(),
                m.getKind(),
                m.getReason(),
                createdBy == null ? null : accounts.get(createdBy),
                m.getCreatedAt(),
                removedBy == null ? null : accounts.get(removedBy),
                m.getRemovedAt(),
                m.getRemovalReason());
    }

    public List<ReviewBoundFeedbackDTO> boundFeedback(Result r) {
        return communication.boundFeedback(r.getWorkspaceId(), r.getId()).stream()
                .map(row -> new ReviewBoundFeedbackDTO(
                        row.getId(), row.getDestination(), row.getState(), row.getDeliveredAt(), row.getWithdrawn()))
                .toList();
    }

    public List<FeedbackDisagreementDTO> disagreements(Result r) {
        return communication.disagreements(r.getWorkspaceId(), r.getId()).stream()
                .map(row -> new FeedbackDisagreementDTO(
                        row.getId(), row.getDestination(), row.getComment(), row.getRespondedAt()))
                .toList();
    }

    public ReviewFeedbackCountsDTO feedbackCounts(Result r) {
        var c = new EnumMap<FeedbackState, Integer>(FeedbackState.class);
        boundFeedback(r).forEach(f -> c.merge(f.state(), 1, Integer::sum));
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

    /**
     * The developer's own feedback on each of {@code results}, read for all of them at once and keyed by result id. A
     * withdrawn piece is left out, as the profile overview and the response guard read it: it accepts no response. A
     * restore returns it with its earlier response.
     */
    public Map<UUID, List<OwnFeedbackRefDTO>> ownFeedback(long workspaceId, Collection<Result> results) {
        Map<UUID, List<OwnFeedbackRefDTO>> byResult = new HashMap<>();
        results.stream()
                .filter(result -> result.getWorkspaceId() == workspaceId && result.getDeveloperId() != null)
                .collect(Collectors.groupingBy(
                        result -> Objects.requireNonNull(result.getDeveloperId()),
                        Collectors.mapping(Result::getId, Collectors.toSet())))
                .forEach((developer, ids) -> {
                    var rows = communication.ownFeedback(workspaceId, developer, ids);
                    if (rows.isEmpty()) return;
                    var feedbackIds = rows.stream()
                            .map(ResultCommunicationRepository.OwnFeedback::getId)
                            .distinct()
                            .toList();
                    var withdrawn = visibility.withdrawnAt(workspaceId, feedbackIds);
                    var copies = communication.ownCopies(workspaceId, feedbackIds).stream()
                            .collect(Collectors.groupingBy(
                                    ResultCommunicationRepository.OwnCopy::getFeedbackId,
                                    Collectors.mapping(
                                            row -> new OwnFeedbackCopyRefDTO(
                                                    row.getId(),
                                                    row.getKind(),
                                                    row.getExternalUrl(),
                                                    row.getPostedAt()),
                                            Collectors.toList())));
                    rows.stream()
                            .filter(row -> !withdrawn.containsKey(row.getId()))
                            .forEach(row -> {
                                var responded = row.getRespondedAt();
                                var response = responded == null
                                        ? null
                                        : new FeedbackResponseDTO(
                                                row.getResolution(), row.getUsefulness(), row.getComment(), responded);
                                byResult.computeIfAbsent(row.getResultId(), ignored -> new ArrayList<>())
                                        .add(new OwnFeedbackRefDTO(
                                                row.getId(),
                                                row.getDestination(),
                                                row.getState(),
                                                row.getDeliveredAt(),
                                                response,
                                                copies.getOrDefault(row.getId(), List.of())));
                            });
                });
        return byResult;
    }
}
