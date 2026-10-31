package de.tum.cit.aet.hephaestus.practices.review.report;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultProofMapper;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedProblemSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.Comparison;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemBaselineState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScanComparisonService {
    private static final int READ_BATCH = 256;
    private final ReviewItemRepository memberships;
    private final ResultRepository results;
    private final ResultProofMapper proof;
    private final ProblemComparison comparison;
    private final ReviewRepository queries;
    private final ScanEvidencePolicy eligibility;
    private final ProblemRepository problems;

    @PersistenceContext
    private @Nullable EntityManager entities;

    private final JsonMapper json;

    public Map<ProblemBaselineState, Integer> compare(
            Review review, @Nullable Review baseline, Map<Long, Comparison> comparisons) {
        var changes = new EnumMap<ProblemBaselineState, Integer>(ProblemBaselineState.class);
        ScanReportCursor after = null;
        while (true) {
            var pairs = queries.comparisonPairs(review, baseline == null ? null : baseline.getId(), after, READ_BATCH);
            if (pairs.isEmpty()) break;
            for (var key : pairs) {
                var member = memberships
                        .findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                                review.getWorkspaceId(), review.getId(), key.practiceId(), key.itemId())
                        .orElseThrow();
                var result = results.findByIdAndWorkspaceId(
                                Objects.requireNonNull(member.getResultId()), review.getWorkspaceId())
                        .orElseThrow();
                var rows = new ArrayList<Result>();
                rows.add(result);
                var priorMember = baseline == null
                        ? null
                        : memberships
                                .findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                                        review.getWorkspaceId(), baseline.getId(), key.practiceId(), key.itemId())
                                .orElse(null);
                var previous = priorMember == null || priorMember.getResultId() == null
                        ? null
                        : results.findByIdAndWorkspaceId(priorMember.getResultId(), review.getWorkspaceId())
                                .orElse(null);
                if (previous != null) rows.add(previous);
                var allowed = eligibility.permitted(review, rows);
                var unmarked = eligibility.unmarked(review, rows);
                var comparisonFacts = new ArrayList<ScanProblemComparison>();
                if (!result.isMigratedHistory() && allowed.contains(result.getId())) {
                    var current = proof.snapshot(result);
                    var currentKeys = comparison.identities(current, key.path());
                    var old = previous != null && !previous.isMigratedHistory() && allowed.contains(previous.getId())
                            ? proof.snapshot(previous)
                            : null;
                    var previousKeys = old == null
                            ? Map.<String, AdmittedProblemSnapshot>of()
                            : comparison.identities(old, key.path());
                    boolean comparable = comparisons.get(member.getPracticeId()) == Comparison.COMPARABLE;
                    for (var entry : currentKeys.entrySet()) {
                        var prior = comparable ? previousKeys.get(entry.getKey()) : null;
                        var state = prior == null
                                ? ProblemBaselineState.NEW
                                : comparison
                                                .content(prior.candidate())
                                                .equals(comparison.content(
                                                        entry.getValue().candidate()))
                                        ? ProblemBaselineState.UNCHANGED
                                        : ProblemBaselineState.UPDATED;
                        comparisonFacts.add(new ScanProblemComparison(
                                entry.getValue().id(), state, prior == null ? null : prior.id()));
                        changes.merge(state, 1, Integer::sum);
                    }
                    if (old != null
                            && previous != null
                            && ProblemComparison.provesAbsence(
                                    old.candidate(),
                                    current.candidate(),
                                    comparable,
                                    member.getState() == ReviewItemState.JUDGED,
                                    !unmarked.contains(result.getId()) || !unmarked.contains(previous.getId()),
                                    allowed.contains(result.getId()) && allowed.contains(previous.getId())))
                        for (var prior : previousKeys.entrySet())
                            if (!currentKeys.containsKey(prior.getKey())) {
                                comparisonFacts.add(new ScanProblemComparison(
                                        prior.getValue().id(),
                                        ProblemBaselineState.ABSENT,
                                        prior.getValue().id()));
                                changes.merge(ProblemBaselineState.ABSENT, 1, Integer::sum);
                            }
                }
                ObjectNode detail = member.getDetail() == null
                        ? json.createObjectNode()
                        : (ObjectNode) member.getDetail().deepCopy();
                detail.set("problemComparison", json.valueToTree(comparisonFacts));
                member.setDetail(detail);
                memberships.saveAndFlush(member);
                for (var row : rows) {
                    problems.findByWorkspaceIdAndResultIdOrderByPositionAsc(review.getWorkspaceId(), row.getId())
                            .forEach(this::detach);
                    detach(row);
                }
                detach(member);
                if (priorMember != null) detach(priorMember);
            }
            if (pairs.size() < READ_BATCH) break;
            after = pairs.getLast();
        }
        return changes;
    }

    private void detach(Object entity) {
        var context = Objects.requireNonNull(entities);
        if (context.contains(entity)) context.detach(entity);
    }
}
