package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import java.util.Collection;
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
@ConditionalOnCaptureRole
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
        Map<Long, PracticeVersion> versionById = versions
                .findByWorkspaceIdAndIdIn(
                        workspaceId,
                        results.stream()
                                .map(Result::getPracticeVersionId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList())
                .stream()
                .collect(Collectors.toMap(version -> Objects.requireNonNull(version.getId()), Function.identity()));
        var reviewById = reviews
                .findAllById(
                        results.stream().map(Result::getReviewId).distinct().toList())
                .stream()
                .filter(review -> review.getWorkspaceId() == workspaceId)
                .collect(Collectors.toMap(Review::getId, Function.identity()));
        var itemById = references.items(
                workspaceId, results.stream().map(Result::getItemId).distinct().toList());
        var sourceStates = freshness.states(workspaceId, results);
        return results.stream()
                .map(result -> new ResultView(
                        result,
                        Objects.requireNonNull(practiceById.get(result.getPracticeId())),
                        versionById.get(result.getPracticeVersionId()),
                        Objects.requireNonNull(reviewById.get(result.getReviewId())),
                        Objects.requireNonNull(itemById.get(result.getItemId())),
                        Objects.requireNonNull(sourceStates.get(result.getId()))))
                .toList();
    }
}
