package de.tum.cit.aet.hephaestus.practices.feedback.thread;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.result.ProblemLineageRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemBaselineState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** What a note on the work cites, and where those problems stand now. */
@Slf4j
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class FeedbackThreadEvidence {
    private final ReviewRepository reviews;
    private final ProblemLineageRepository lineage;
    private final ResultRepository results;
    private final ObjectMapper mapper;

    /** What the lineage says about a note's problems now. */
    enum Verdict {
        /** Every cited problem is No longer present. */
        GONE,
        /** At least one cited problem is present: a later review found it as New, Unchanged or Updated. */
        PRESENT,
        /** Neither is proven: a problem has no usable lineage, or a newer review measured it another way. */
        UNKNOWN
    }

    /** A verdict and the results whose lineage decided it. */
    record Standing(Verdict verdict, Set<UUID> deciding) {}

    /**
     * The problems that the note of one copy cites, from the composition support its revision was prepared with. Empty
     * when the support is missing or rests on a result without a problem: such a note never resolves.
     */
    List<UUID> cited(long workspaceId, UUID reviewId, UUID feedbackId, String kind, int position) {
        var node = reviews.findByIdAndWorkspaceId(reviewId, workspaceId)
                .map(review -> review.getChecks().path("feedbackComposition").path(feedbackId.toString()))
                .orElse(null);
        if (node == null || !node.isObject()) return List.of();
        FeedbackCompositionTrace trace;
        try {
            trace = mapper.treeToValue(node, FeedbackCompositionTrace.class);
        } catch (RuntimeException unreadable) {
            // Support from an older shape cannot say what the note cites, so the note never resolves.
            log.debug("Composition support is unreadable: feedbackId={}", feedbackId);
            return List.of();
        }
        var problems = new ArrayList<UUID>();
        for (var copy : trace.copies()) {
            if (!copy.kind().name().equals(kind) || copy.position() != position) continue;
            for (var support : copy.basedOn()) {
                if (support.problemId() == null) return List.of();
                problems.add(support.problemId());
            }
        }
        return List.copyOf(problems);
    }

    /**
     * Whether {@code resultId} proved one of the cited problems No longer present. A result that the note itself rests on
     * proves nothing about its repair.
     */
    boolean proved(long workspaceId, UUID resultId, UUID feedbackId, List<UUID> cited) {
        return !cited.isEmpty()
                && results.findBasisOfFeedback(workspaceId, feedbackId).stream()
                        .noneMatch(result -> result.getId().equals(resultId))
                && lineage.namesAny(workspaceId, resultId, cited);
    }

    Standing standing(long workspaceId, List<UUID> cited) {
        if (cited.isEmpty()) return new Standing(Verdict.UNKNOWN, Set.of());
        var rows = lineage.findStanding(workspaceId, cited);
        var deciding = new HashSet<UUID>();
        var gone = new HashSet<UUID>();
        boolean present = false;
        for (var row : rows) {
            if (row.getUnchained()) continue;
            deciding.add(row.getResultId());
            if (ProblemBaselineState.ABSENT.name().equals(row.getComparison())) gone.add(row.getProblemId());
            else present = true;
        }
        var verdict = present ? Verdict.PRESENT : gone.containsAll(cited) ? Verdict.GONE : Verdict.UNKNOWN;
        return new Standing(verdict, Set.copyOf(deciding));
    }
}
