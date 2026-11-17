package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.model.ResultFacts;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewPracticeRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewPractice;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResultViewQuery {

    private final PracticeRepository practices;
    private final PracticeVersionRepository versions;
    private final ReviewRepository reviews;
    private final ReviewPracticeRepository reviewPractices;
    private final ProductReferenceQuery references;
    private final ResultRepository results;
    private final ResultFreshness freshness;

    public Optional<ResultView> latestForDeveloper(long workspaceId, long developerId) {
        return results.findFirstByWorkspaceIdAndDeveloperIdOrderByRecordedAtDescIdDesc(workspaceId, developerId)
                .map(result -> views(workspaceId, List.of(result.facts()), true).getFirst());
    }

    /** Views with the feedback mode that each observation was sealed in, for every developer read. */
    public List<ResultView> views(long workspaceId, Collection<ResultFacts> results) {
        return prefetched(views(workspaceId, results, true));
    }

    /**
     * Views whose freshness is read on first use, for a read that asks about few of them or none: a standing asks only
     * about the results it quotes. A look-back of many results then does not read the work behind each one.
     */
    public List<ResultView> viewsOnDemand(long workspaceId, Collection<ResultFacts> results) {
        return views(workspaceId, results, true);
    }

    /**
     * Views for an operator's read of review results, lineage and scan reports. It shows every observation and
     * never asks whether one counts, so it skips the feedback-mode read: each view's mode is null.
     */
    public List<ResultView> operatorViews(long workspaceId, Collection<ResultFacts> results) {
        return prefetched(views(workspaceId, results, false));
    }

    // A read that shows each view's freshness reads it for all of them at once, not once per view.
    private static List<ResultView> prefetched(List<ResultView> views) {
        SourceFreshness.prefetch(views);
        return views;
    }

    private List<ResultView> views(long workspaceId, Collection<ResultFacts> results, boolean sealedFeedbackModes) {
        if (results.isEmpty()) {
            return List.of();
        }
        if (results.stream().anyMatch(result -> result.workspaceId() != workspaceId)) {
            throw new IllegalArgumentException("Results do not belong to the workspace");
        }
        Map<Long, Practice> practiceById = practices
                .findByWorkspaceIdAndIdIn(
                        workspaceId,
                        results.stream().map(ResultFacts::practiceId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(practice -> Objects.requireNonNull(practice.getId()), Function.identity()));
        // A practice arrives with its current version; only a result pinned to an older one needs a read.
        Map<Long, PracticeVersion> versionById = new HashMap<>();
        practiceById.values().forEach(practice -> {
            var current = practice.getCurrentVersion();
            versionById.put(Objects.requireNonNull(current.getId()), current);
        });
        var pinnedElsewhere = results.stream()
                .map(ResultFacts::practiceVersionId)
                .filter(Objects::nonNull)
                .filter(id -> !versionById.containsKey(id))
                .distinct()
                .toList();
        if (!pinnedElsewhere.isEmpty())
            versions.findByWorkspaceIdAndIdIn(workspaceId, pinnedElsewhere)
                    .forEach(version -> versionById.put(Objects.requireNonNull(version.getId()), version));
        var reviewById = reviews
                .findAllById(
                        results.stream().map(ResultFacts::reviewId).distinct().toList())
                .stream()
                .filter(review -> review.getWorkspaceId() == workspaceId)
                .collect(Collectors.toMap(Review::getId, Function.identity()));
        var itemById = references.referenced(
                workspaceId,
                results.stream().map(ResultFacts::itemId).distinct().toList());
        var activeItems = itemById.entrySet().stream()
                .filter(entry -> entry.getValue().active())
                .collect(Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().ref()));
        var sourceStates = new SourceFreshness(rows -> freshness.freshness(workspaceId, rows, activeItems));
        var sealedModes = new HashMap<ReviewPractice.Id, FeedbackMode>();
        if (sealedFeedbackModes)
            reviewPractices
                    .findSealedFeedbackModes(workspaceId, reviewById.keySet())
                    .forEach(recorded -> sealedModes.put(
                            new ReviewPractice.Id(recorded.reviewId(), recorded.practiceId()), recorded.mode()));
        return results.stream()
                .map(result -> new ResultView(
                        result,
                        Objects.requireNonNull(practiceById.get(result.practiceId())),
                        versionById.get(result.practiceVersionId()),
                        Objects.requireNonNull(reviewById.get(result.reviewId())),
                        Objects.requireNonNull(itemById.get(result.itemId())).reference(),
                        sourceStates,
                        sealedModes.get(new ReviewPractice.Id(result.reviewId(), result.practiceId()))))
                .toList();
    }
}
