package de.tum.cit.aet.hephaestus.practices.feedback.page;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasis;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultViewQuery;
import de.tum.cit.aet.hephaestus.practices.result.ResultVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.result.trend.WorkResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnCaptureRole
public class PracticePageFeedbackBasis {

    private final FeedbackBasisRepository basis;
    private final ResultRepository results;
    private final PracticeRepository practices;
    private final PracticeVersionRepository versions;
    private final ResultVisibilityPolicy visibility;
    private final ResultViewQuery views;

    public Map<UUID, List<Result>> visibleEvidence(long workspaceId, Collection<UUID> feedbackIds) {
        if (feedbackIds.isEmpty()) {
            return Map.of();
        }
        List<FeedbackBasis> edges = basis.findByWorkspaceIdAndFeedbackIdInOrderByOrdinalAsc(workspaceId, feedbackIds);
        var rows = results.findByWorkspaceIdAndIdIn(
                workspaceId,
                edges.stream().map(FeedbackBasis::getResultId).distinct().toList());
        var permitted = visibility.permitsShown(workspaceId, rows, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        Map<UUID, Result> byId = rows.stream().collect(Collectors.toMap(Result::getId, Function.identity()));
        Map<UUID, List<FeedbackBasis>> byFeedback =
                edges.stream().collect(Collectors.groupingBy(FeedbackBasis::getFeedbackId));
        Map<UUID, List<Result>> visible = new LinkedHashMap<>();
        byFeedback.forEach((feedbackId, support) -> {
            if (support.stream().allMatch(edge -> permitted.contains(edge.getResultId()))) {
                visible.put(
                        feedbackId,
                        support.stream()
                                .map(edge -> Objects.requireNonNull(byId.get(edge.getResultId())))
                                .distinct()
                                .sorted(Comparator.comparing(Result::getRecordedAt)
                                        .reversed())
                                .toList());
            }
        });
        return visible;
    }

    public Map<Long, Practice> practices(long workspaceId, Collection<Result> results) {
        var ids = results.stream().map(Result::getPracticeId).collect(Collectors.toSet());
        return practices.findByWorkspaceIdAndIdIn(workspaceId, ids).stream()
                .collect(Collectors.toMap(Practice::getId, Function.identity()));
    }

    public Map<UUID, Instant> practiceChangedAt(Map<UUID, List<Result>> evidenceByFeedback) {
        Map<Long, PracticeVersion> pinnedById = new HashMap<>();
        Map<Long, List<PracticeVersion>> versionsByPractice = new HashMap<>();
        evidenceByFeedback.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.groupingBy(Result::getWorkspaceId))
                .forEach((workspaceId, evidence) -> {
                    var pinnedIds = evidence.stream()
                            .map(Result::getPracticeVersionId)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());
                    if (pinnedIds.isEmpty()) return;
                    versions.findByWorkspaceIdAndIdIn(workspaceId, pinnedIds)
                            .forEach(version -> pinnedById.put(Objects.requireNonNull(version.getId()), version));
                    versions.findByWorkspaceIdAndPracticeIdInOrderByNumberAsc(
                                    workspaceId,
                                    evidence.stream().map(Result::getPracticeId).collect(Collectors.toSet()))
                            .forEach(version -> versionsByPractice
                                    .computeIfAbsent(
                                            Objects.requireNonNull(
                                                    version.getPractice().getId()),
                                            ignored -> new ArrayList<>())
                                    .add(version));
                });
        Map<UUID, Instant> changed = new LinkedHashMap<>();
        evidenceByFeedback.forEach((feedbackId, evidence) -> {
            Instant first = null;
            for (Result result : evidence) {
                Long pinnedId = result.getPracticeVersionId();
                var pinned = pinnedId == null ? null : pinnedById.get(pinnedId);
                if (pinned == null) {
                    continue;
                }
                var changedVersion = versionsByPractice.getOrDefault(result.getPracticeId(), List.of()).stream()
                        .filter(version -> version.getNumber() > pinned.getNumber())
                        .filter(version -> !version.getReviewDigest().equals(pinned.getReviewDigest()))
                        .findFirst();
                if (changedVersion.isPresent()
                        && (first == null || changedVersion.get().getCreatedAt().isBefore(first))) {
                    first = changedVersion.get().getCreatedAt();
                }
            }
            if (first != null) {
                changed.put(feedbackId, first);
            }
        });
        return changed;
    }

    public static @Nullable Instant resolvedByDeveloperAt(@Nullable FeedbackResponseDTO response) {
        if (response == null) {
            return null;
        }
        return response.resolution() == FeedbackResolution.ADDRESSED
                        || response.resolution() == FeedbackResolution.NOT_APPLICABLE
                ? response.createdAt()
                : null;
    }

    public Map<UUID, WorkResolution> workResolutions(
            long workspaceId,
            Collection<Feedback> feedback,
            Map<UUID, List<Result>> evidence,
            Map<UUID, Instant> practiceChangedAt,
            Instant now) {
        var withEvidence = feedback.stream()
                .filter(piece -> evidence.containsKey(piece.getId()))
                .toList();
        var oldest = withEvidence.stream().map(Feedback::getCreatedAt).min(Comparator.naturalOrder());
        if (oldest.isEmpty()) {
            return Map.of();
        }
        var developers = withEvidence.stream().map(Feedback::getDeveloperId).collect(Collectors.toSet());
        var later = basis.findLaterResults(workspaceId, developers, oldest.get(), now);
        var allowed = visibility.permitsAll(workspaceId, later, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        var practiceIds = withEvidence.stream()
                .map(piece -> Objects.requireNonNull(evidence.get(piece.getId())))
                .filter(support -> !support.isEmpty())
                .map(support -> support.getFirst().getPracticeId())
                .collect(Collectors.toSet());
        var opportunities = views.views(
                workspaceId,
                later.stream()
                        .filter(row -> allowed.contains(row.getId()) && practiceIds.contains(row.getPracticeId()))
                        .toList());
        Map<UUID, WorkResolution> resolved = new LinkedHashMap<>();
        for (Feedback piece : withEvidence) {
            List<Result> support = evidence.get(piece.getId());
            if (support == null || support.isEmpty() || practiceChangedAt.containsKey(piece.getId())) {
                continue;
            }
            long practiceId = support.getFirst().getPracticeId();
            var current = opportunities.stream()
                    .filter(view -> view.result().getPracticeId() == practiceId)
                    .filter(view -> Objects.equals(view.result().getDeveloperId(), piece.getDeveloperId()))
                    .toList();
            resolved.put(
                    piece.getId(),
                    WorkResolution.Opportunities.of(current, Instant.EPOCH).resolve(piece.getCreatedAt()));
        }
        return resolved;
    }

    public Map<UUID, Instant> resolvedByWorkAt(long workspaceId, Collection<Feedback> feedback, Instant now) {
        var evidence = visibleEvidence(
                workspaceId, feedback.stream().map(Feedback::getId).toList());
        Map<UUID, Instant> resolved = new LinkedHashMap<>();
        workResolutions(workspaceId, feedback, evidence, practiceChangedAt(evidence), now)
                .forEach((id, resolution) -> {
                    Instant at = resolution.resolvedAt();
                    if (at != null) {
                        resolved.put(id, at);
                    }
                });
        return resolved;
    }

    public UUID resultId(long workspaceId, long practiceId, WorkResolution.Work work) {
        return results.findByWorkspaceIdAndReviewIdOrderByRecordedAtAscIdAsc(workspaceId, work.reviewId()).stream()
                .filter(result -> result.getPracticeId() == practiceId && result.getItemId() == work.itemId())
                .max(Comparator.comparing(Result::getRecordedAt)
                        .thenComparing(result -> result.getId().toString()))
                .orElseThrow(() -> new IllegalStateException("Resolution work has no retained result."))
                .getId();
    }
}
