package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.ProblemLineage;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.report.ProblemComparison;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanEvidencePolicy;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedProblemSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.CapturedTextProbe;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemBaselineState;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Records how the problems of a newly written item review result compare with the previous comparable result (ADR 0069
 * L12). Identity, comparability and the proof of absence are the scan comparison's: a problem matches by its key or its
 * first citation, and No longer present needs a judged result of a changed version, unmarked and with
 * trusted coverage. An item review adds one condition, because its problems can cite any path and the model chooses
 * their keys: every text that the previous problem quoted must be gone from the newer capture. A renamed key or a
 * problem the model left out is then never mistaken for a repair.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class ProblemLineageWriter {
    private final ResultRepository results;
    private final ResultProofMapper proof;
    private final ProblemComparison comparison;
    private final ScanEvidencePolicy evidence;
    private final ProblemLineageRepository lineage;

    void record(
            long ws,
            Review review,
            Result result,
            ResultCandidate written,
            List<Problem> problems,
            CapturedTextProbe captured,
            Instant at) {
        // A trial writes no result. A scan compares its files in its own report. A result that ended at once is no
        // current decision: a newer result compares instead.
        if (review.getKind() != ReviewKind.ITEM || result.getEndedAt() != null || result.getPracticeVersionId() == null)
            return;
        var prior = results.findPreviousComparable(ws, result.getId()).orElse(null);
        if (prior == null || prior.isMigratedHistory() || !prior.isAdmissibleProof()) return;
        var rows = List.of(result, prior);
        AdmittedResultSnapshot before;
        try {
            before = proof.snapshot(prior);
        } catch (IllegalArgumentException unverifiable) {
            // Proof that no longer verifies, such as a problem without a basis from before 1.0, compares with nothing.
            return;
        }
        var after = new AdmittedResultSnapshot(result.getId(), written, snapshots(written, problems));
        var previousKeys = comparison.itemIdentities(before);
        var currentKeys = comparison.itemIdentities(after);
        var recorded = new ArrayList<ProblemLineage>();
        for (var entry : currentKeys.entrySet()) {
            var match = previousKeys.get(entry.getKey());
            var state = match == null
                    ? ProblemBaselineState.NEW
                    : comparison
                                    .itemContent(match.candidate())
                                    .equals(comparison.itemContent(
                                            entry.getValue().candidate()))
                            ? ProblemBaselineState.UNCHANGED
                            : ProblemBaselineState.UPDATED;
            recorded.add(row(ws, result, prior, entry.getValue().id(), match == null ? null : match.id(), state, at));
        }
        boolean unmarked = evidence.unmarked(review, rows).containsAll(List.of(result.getId(), prior.getId()));
        if (ProblemComparison.provesAbsence(before.candidate(), written, true, true, !unmarked, true))
            for (var entry : previousKeys.entrySet()) {
                if (currentKeys.containsKey(entry.getKey())) continue;
                var cited = entry.getValue().candidate().citations();
                if (!cited.isEmpty()
                        && cited.stream()
                                .allMatch(citation -> captured.presence(citation) == CapturedTextProbe.Presence.GONE))
                    recorded.add(row(ws, result, prior, null, entry.getValue().id(), ProblemBaselineState.ABSENT, at));
            }
        lineage.saveAll(recorded);
    }

    /** The written problems with their row ids, in the candidate's order. */
    private static List<AdmittedProblemSnapshot> snapshots(ResultCandidate written, List<Problem> problems) {
        var byPosition = problems.stream()
                .sorted(Comparator.comparingInt(Problem::getPosition))
                .toList();
        var candidates = written.problems().stream()
                .sorted(Comparator.comparingInt(ProblemCandidate::position))
                .toList();
        var snapshots = new ArrayList<AdmittedProblemSnapshot>();
        for (int index = 0; index < candidates.size(); index++)
            snapshots.add(new AdmittedProblemSnapshot(byPosition.get(index).getId(), candidates.get(index)));
        return snapshots;
    }

    private static ProblemLineage row(
            long ws,
            Result result,
            Result prior,
            @Nullable UUID problem,
            @Nullable UUID priorProblem,
            ProblemBaselineState state,
            Instant at) {
        return ProblemLineage.builder()
                .id(UUID.randomUUID())
                .workspaceId(ws)
                .resultId(result.getId())
                .priorResultId(Objects.requireNonNull(prior.getId()))
                .problemId(problem)
                .priorProblemId(priorProblem)
                .comparison(state)
                .recordedAt(at)
                .build();
    }
}
