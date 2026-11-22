package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.practices.metrics.PracticeTrustMeters;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewOrder;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.report.ProblemComparison;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedProblemSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.CapturedTextProbe;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ResultClaimWriter {
    private final ItemIndexService itemLocks;
    private final ResultRepository results;
    private final ResultCurrentness currentness;
    private final ReviewRepository reviews;
    private final ProblemRepository problems;
    private final JsonMapper json;
    private final PracticeTrustMeters trust;
    private final ProblemLineageWriter lineage;
    private final ProblemComparison comparison;

    public Result write(long ws, Review review, ResultCandidate c, Instant at, CapturedTextProbe captured) {
        itemLocks.lock(ws, c.itemId());
        var reviewId = review.getId();
        var result = Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(ws)
                .reviewId(reviewId)
                .practiceId(c.practiceId())
                .practiceVersionId(c.practiceVersionId())
                .itemId(c.itemId())
                .itemVersion(c.itemVersion())
                .developerId(c.developerId())
                .outcome(c.outcome())
                .summary(c.summary())
                .explanation(c.explanation())
                .severity(c.problems().stream()
                        .map(ProblemCandidate::severity)
                        .min(Comparator.comparingInt(Severity::rank))
                        .orElse(null))
                .evidence(json.valueToTree(c.evidence()))
                .example(c.example() == null ? null : json.valueToTree(c.example()))
                .coverage(json.valueToTree(c.coverage()))
                .recordedAt(at)
                .build();
        var stale = currentness.prepareClaim(ws, result, at);
        if (stale != null) result.end(stale, at);
        var current = stale == null
                ? results.findCurrentClaim(ws, c.practiceId(), c.itemId(), c.developerId())
                : Optional.<Result>empty();
        if (current.isPresent()) {
            var old = results.lockByIdAndWorkspaceId(current.orElseThrow().getId(), ws)
                    .orElseThrow();
            var previous = reviews.findByIdAndWorkspaceId(old.getReviewId(), ws).orElseThrow();
            if (new ReviewOrder(review.getOrderedAt(), at, reviewId)
                            .compareTo(new ReviewOrder(previous.getOrderedAt(), old.getRecordedAt(), previous.getId()))
                    <= 0) result.end(ResultEndReason.OUTRANKED, at);
            else {
                old.end(ResultEndReason.REPLACED, at.isBefore(old.getRecordedAt()) ? old.getRecordedAt() : at);
                results.flush();
            }
        }
        results.saveAndFlush(result);
        var outcome = result.getOutcome();
        TransactionCallbacks.afterCommit(() -> trust.recorded(outcome));
        var ids = c.problems().stream().map(child -> UUID.randomUUID()).toList();
        var identities = identities(ws, review, result.getId(), c, ids);
        var written = new ArrayList<Problem>();
        for (int index = 0; index < ids.size(); index++) {
            var child = c.problems().get(index);
            var id = ids.get(index);
            written.add(problems.save(Problem.builder()
                    .id(id)
                    .workspaceId(ws)
                    .resultId(result.getId())
                    .position(child.position())
                    .key(child.key())
                    .severity(child.severity())
                    .summary(child.summary())
                    .explanation(child.explanation())
                    .citations(json.valueToTree(child.citations()))
                    .basis(json.valueToTree(child.basis()))
                    .nextStep(child.nextStep())
                    .identity(identities.get(id))
                    .build()));
        }
        lineage.record(ws, review, result, c, written, captured, at);
        return result;
    }

    /**
     * Each problem's identity by its id, as a later comparison matches it: a scan's against its own file, an item
     * review's by its first citation. A problem that has none is absent.
     */
    private Map<UUID, String> identities(long ws, Review review, UUID resultId, ResultCandidate c, List<UUID> ids) {
        var snapshots = new ArrayList<AdmittedProblemSnapshot>();
        for (int index = 0; index < ids.size(); index++)
            snapshots.add(
                    new AdmittedProblemSnapshot(ids.get(index), c.problems().get(index)));
        snapshots.sort(Comparator.comparingInt(problem -> problem.candidate().position()));
        var result = new AdmittedResultSnapshot(resultId, c, snapshots);
        var byIdentity = review.getKind() == ReviewKind.SCAN
                ? comparison.identities(
                        result,
                        Objects.requireNonNullElse(
                                itemLocks
                                        .find(ws, c.itemId())
                                        .map(item -> item.ref().path())
                                        .orElse(null),
                                ""))
                : comparison.itemIdentities(result);
        var identities = new HashMap<UUID, String>();
        byIdentity.forEach((identity, problem) -> identities.put(problem.id(), identity));
        return identities;
    }
}
