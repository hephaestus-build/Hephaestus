package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
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
    private final ProductReferenceQuery references;
    private final ResultRepository results;
    private final ResultFreshness freshness;

    public Optional<ResultView> latestForDeveloper(long workspaceId, long developerId) {
        return results.findFirstByWorkspaceIdAndDeveloperIdOrderByRecordedAtDescIdDesc(workspaceId, developerId)
                .map(result -> views(workspaceId, List.of(result)).getFirst());
    }

    public List<ResultView> views(long workspaceId, Collection<Result> results) {
        if (results.isEmpty()) {
            return List.of();
        }
        if (results.stream().anyMatch(result -> result.getWorkspaceId() != workspaceId)) {
            throw new IllegalArgumentException("Results do not belong to the workspace");
        }
        Map<Long, Practice> practiceById = practices
                .findByWorkspaceIdAndIdIn(
                        workspaceId,
                        results.stream().map(Result::getPracticeId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(practice -> Objects.requireNonNull(practice.getId()), Function.identity()));
        // A practice arrives with its current version; only a result pinned to an older one needs a read.
        Map<Long, PracticeVersion> versionById = new HashMap<>();
        practiceById.values().forEach(practice -> {
            var current = practice.getCurrentVersion();
            versionById.put(Objects.requireNonNull(current.getId()), current);
        });
        var pinnedElsewhere = results.stream()
                .map(Result::getPracticeVersionId)
                .filter(Objects::nonNull)
                .filter(id -> !versionById.containsKey(id))
                .distinct()
                .toList();
        if (!pinnedElsewhere.isEmpty())
            versions.findByWorkspaceIdAndIdIn(workspaceId, pinnedElsewhere)
                    .forEach(version -> versionById.put(Objects.requireNonNull(version.getId()), version));
        var reviewById = reviews
                .findAllById(
                        results.stream().map(Result::getReviewId).distinct().toList())
                .stream()
                .filter(review -> review.getWorkspaceId() == workspaceId)
                .collect(Collectors.toMap(Review::getId, Function.identity()));
        var itemById = references.referenced(
                workspaceId, results.stream().map(Result::getItemId).distinct().toList());
        var activeItems = itemById.entrySet().stream()
                .filter(entry -> entry.getValue().active())
                .collect(Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().ref()));
        var sourceStates = freshness.states(workspaceId, results, activeItems);
        return results.stream()
                .map(result -> new ResultView(
                        result,
                        Objects.requireNonNull(practiceById.get(result.getPracticeId())),
                        versionById.get(result.getPracticeVersionId()),
                        Objects.requireNonNull(reviewById.get(result.getReviewId())),
                        Objects.requireNonNull(itemById.get(result.getItemId())).reference(),
                        Objects.requireNonNull(sourceStates.get(result.getId()))))
                .toList();
    }
}
