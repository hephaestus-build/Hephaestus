package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackBasisDTO;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultViewQuery;
import de.tum.cit.aet.hephaestus.practices.result.ResultVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.ReviewResultQueryService;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedbackBasisRead {
    private final FeedbackBasisRepository basis;
    private final ResultRepository results;
    private final ResultViewQuery views;
    private final ResultVisibilityPolicy evidence;
    private final ReviewResultQueryService presentation;

    public record Snapshot(
            List<FeedbackBasisRepository.ReadRow> edges, List<Result> results, Set<UUID> permitted, boolean readable) {
        public Snapshot {
            edges = List.copyOf(edges);
            results = List.copyOf(results);
            permitted = Set.copyOf(permitted);
        }
    }

    public Snapshot read(long workspaceId, UUID feedbackId) {
        return Objects.requireNonNull(readAll(workspaceId, List.of(feedbackId)).get(feedbackId));
    }

    public Map<UUID, Snapshot> readAll(long workspaceId, Collection<UUID> feedbackIds) {
        if (feedbackIds.isEmpty()) return Map.of();
        var edges = basis.findReadRows(workspaceId, feedbackIds);
        var ids = edges.stream()
                .map(FeedbackBasisRepository.ReadRow::resultId)
                .distinct()
                .toList();
        var support = ids.isEmpty() ? List.<Result>of() : results.findByWorkspaceIdAndIdIn(workspaceId, ids);
        var permitted = evidence.permitsHistory(workspaceId, support, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        var byResult = support.stream().collect(Collectors.toMap(Result::getId, Function.identity()));
        var byFeedback = edges.stream().collect(Collectors.groupingBy(FeedbackBasisRepository.ReadRow::feedbackId));
        Map<UUID, Snapshot> snapshots = new LinkedHashMap<>();
        for (var id : feedbackIds) {
            var selected = byFeedback.getOrDefault(id, List.of());
            var resultIds = selected.stream()
                    .map(FeedbackBasisRepository.ReadRow::resultId)
                    .distinct()
                    .toList();
            var selectedResults = resultIds.stream()
                    .map(byResult::get)
                    .filter(Objects::nonNull)
                    .toList();
            var selectedPermitted =
                    resultIds.stream().filter(permitted::contains).collect(Collectors.toSet());
            snapshots.put(
                    id,
                    new Snapshot(
                            selected,
                            selectedResults,
                            selectedPermitted,
                            !selected.isEmpty()
                                    && selectedResults.size() == resultIds.size()
                                    && selectedPermitted.containsAll(resultIds)));
        }
        return snapshots;
    }

    public List<FeedbackBasisDTO> bound(long workspaceId, Snapshot snapshot) {
        var resultViews = views.views(workspaceId, snapshot.results()).stream()
                .collect(Collectors.toMap(view -> view.result().getId(), view -> view));
        return snapshot.edges().stream()
                .map(edge -> new FeedbackBasisDTO(
                        edge.resultId(),
                        edge.problemId(),
                        edge.ordinal(),
                        presentation.bound(
                                Objects.requireNonNull(resultViews.get(edge.resultId())),
                                snapshot.permitted().contains(edge.resultId()))))
                .toList();
    }
}
