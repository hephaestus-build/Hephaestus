package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.ReviewBoundFeedbackDTO;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultViewQuery;
import de.tum.cit.aet.hephaestus.practices.result.dto.ProblemSummaryDTO;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewSummaryQueryService;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewLineageDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewLineageEdgeDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewLineageNodeDTO;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.LineageKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewLineageService {
    private static final int LIMIT = 100;
    private final ReviewRepository reviews;
    private final ResultRepository results;
    private final ProblemRepository problems;
    private final ReviewLineageRepository lineage;
    private final ReviewSummaryQueryService reviewQuery;
    private final ResultViewQuery views;
    private final ReviewResultQueryService resultQuery;
    private final ProductReferenceQuery references;
    private final EvidenceAuthorization authorization;

    public ReviewLineageDTO graph(long ws, ReviewLineageFilterParams filter) {
        var kind = filter.kind();
        var itemId = filter.itemId();
        var reviewId = filter.reviewId();
        var resultId = filter.resultId();
        var problemId = filter.problemId();
        var feedbackId = filter.feedbackId();
        if (Stream.of(itemId, reviewId, resultId, problemId, feedbackId)
                        .filter(Objects::nonNull)
                        .count()
                != 1) throw new IllegalArgumentException("Select exactly one lineage record.");
        Object root = switch (kind) {
            case ITEM -> itemId;
            case REVIEW -> reviewId;
            case RESULT -> resultId;
            case PROBLEM -> problemId;
            case FEEDBACK -> feedbackId;
        };
        if (root == null) throw new IllegalArgumentException("The selected id must match the lineage kind.");
        var nodes = new LinkedHashMap<String, ReviewLineageNodeDTO>();
        var edges = new ArrayList<ReviewLineageEdgeDTO>();
        var reviewIds = new LinkedHashSet<UUID>();
        var resultIds = new LinkedHashSet<UUID>();
        var feedbackIds = new LinkedHashSet<UUID>();
        if (kind == LineageKind.ITEM) {
            var item = references
                    .items(ws, List.of(Objects.requireNonNull(itemId)))
                    .get(itemId);
            if (item == null) throw missing(root);
            nodes.put(key(kind, root), new ReviewLineageNodeDTO(key(kind, root), kind, item, null, null, null, null));
            reviews.findByWorkspaceIdAndItemIdOrderByOrderedAtDesc(ws, itemId).stream()
                    .limit(LIMIT)
                    .map(r -> r.getId())
                    .forEach(reviewIds::add);
        }
        if (kind == LineageKind.REVIEW) {
            reviewIds.add(Objects.requireNonNull(reviewId));
            reviewQuery.require(ws, reviewId);
        }
        if (kind == LineageKind.PROBLEM) {
            var problem = problems.findByIdAndWorkspaceId(Objects.requireNonNull(problemId), ws)
                    .orElseThrow(() -> missing(root));
            resultIds.add(problem.getResultId());
        }
        if (kind == LineageKind.RESULT) resultIds.add(Objects.requireNonNull(resultId));
        if (kind == LineageKind.FEEDBACK) {
            feedbackIds.add(Objects.requireNonNull(feedbackId));
            if (lineage.findFeedback(ws, feedbackIds).isEmpty()) throw missing(root);
            lineage.findBindings(ws, feedbackIds).forEach(row -> resultIds.add(row.getResultId()));
        }
        for (var id : List.copyOf(resultIds)) {
            var result = results.findByIdAndWorkspaceId(id, ws).orElseThrow(() -> missing(root));
            reviewIds.add(result.getReviewId());
        }
        for (var id : List.copyOf(reviewIds)) {
            var review = reviewQuery.require(ws, id);
            var item = reviewQuery.item(review);
            String itemKey = key(LineageKind.ITEM, item.id()), reviewKey = key(LineageKind.REVIEW, id);
            nodes.put(itemKey, new ReviewLineageNodeDTO(itemKey, LineageKind.ITEM, item, null, null, null, null));
            nodes.put(
                    reviewKey,
                    new ReviewLineageNodeDTO(
                            reviewKey, LineageKind.REVIEW, null, reviewQuery.ref(review), null, null, null));
            edges.add(new ReviewLineageEdgeDTO(itemKey, reviewKey, "REVIEWED_BY"));
            lineage.findResultsOfReview(ws, id, LIMIT).forEach(row -> resultIds.add(row.getId()));
            lineage.findFeedbackIdsOfReview(ws, id).stream().limit(LIMIT).forEach(feedbackIds::add);
        }
        var rows = results.findByWorkspaceIdAndIdIn(ws, resultIds);
        var permitted = authorization.permitsAll(ws, rows, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW);
        for (var v : views.views(ws, rows)) {
            var r = v.result();
            var resultKey = key(LineageKind.RESULT, r.getId());
            nodes.put(
                    resultKey,
                    new ReviewLineageNodeDTO(
                            resultKey,
                            LineageKind.RESULT,
                            null,
                            null,
                            resultQuery.bound(v, permitted.contains(r.getId())),
                            null,
                            null));
            edges.add(new ReviewLineageEdgeDTO(key(LineageKind.REVIEW, r.getReviewId()), resultKey, "RECORDED"));
            for (var child : problems.findByWorkspaceIdAndResultIdOrderByPositionAsc(ws, r.getId()).stream()
                    .limit(LIMIT)
                    .toList()) {
                var childKey = key(LineageKind.PROBLEM, child.getId());
                nodes.put(
                        childKey,
                        new ReviewLineageNodeDTO(
                                childKey,
                                LineageKind.PROBLEM,
                                null,
                                null,
                                null,
                                new ProblemSummaryDTO(
                                        child.getId(),
                                        child.getSeverity(),
                                        permitted.contains(r.getId()) ? child.getSummary() : "Evidence hidden"),
                                null));
                edges.add(new ReviewLineageEdgeDTO(resultKey, childKey, "RECORDS"));
            }
        }
        for (var f : lineage.findFeedback(ws, feedbackIds)) {
            var feedbackKey = key(LineageKind.FEEDBACK, f.getId());
            nodes.put(
                    feedbackKey,
                    new ReviewLineageNodeDTO(
                            feedbackKey,
                            LineageKind.FEEDBACK,
                            null,
                            null,
                            null,
                            null,
                            new ReviewBoundFeedbackDTO(
                                    f.getId(),
                                    FeedbackDestination.valueOf(f.getDestination()),
                                    FeedbackState.valueOf(f.getState()),
                                    f.getDeliveredAt(),
                                    f.getWithdrawn())));
        }
        if (!feedbackIds.isEmpty())
            lineage.findBindings(ws, feedbackIds).forEach(row -> {
                var feedbackKey = key(LineageKind.FEEDBACK, row.getFeedbackId());
                edges.add(new ReviewLineageEdgeDTO(key(LineageKind.RESULT, row.getResultId()), feedbackKey, "BASIS"));
                var boundProblemId = row.getProblemId();
                if (boundProblemId != null)
                    edges.add(new ReviewLineageEdgeDTO(key(LineageKind.PROBLEM, boundProblemId), feedbackKey, "BASIS"));
            });
        return new ReviewLineageDTO(
                key(kind, root),
                List.copyOf(nodes.values()),
                edges.stream()
                        .filter(e -> nodes.containsKey(e.from()) && nodes.containsKey(e.to()))
                        .distinct()
                        .toList());
    }

    private static String key(LineageKind kind, Object id) {
        return kind.name().toLowerCase(Locale.ROOT) + ":" + id;
    }

    private static EntityNotFoundException missing(Object id) {
        return new EntityNotFoundException("Lineage", id.toString());
    }
}
