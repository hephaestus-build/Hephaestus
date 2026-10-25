package de.tum.cit.aet.hephaestus.practices.trace;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.ReviewOn;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewPracticeRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewSummaryQueryService;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.review.dto.ItemEventDTO;
import de.tum.cit.aet.hephaestus.practices.review.feedbackmode.FeedbackModeResolver;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewPractice;
import de.tum.cit.aet.hephaestus.practices.trace.dto.ItemTraceDTO;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ItemTraceQueryService {
    private final ProductReferenceQuery references;
    private final ReviewSummaryQueryService reviewQuery;
    private final ReviewPracticeRepository assignments;
    private final ResultRepository results;
    private final PracticeRepository practices;
    private final PracticeVersionRepository versions;
    private final WorkspaceReviewDefaultsProvider defaults;
    private final JsonMapper json;
    private final ItemTraceRepository ledger;

    public ItemTraceDTO trace(long ws, long itemId) {
        var item = requireItem(ws, itemId);
        var history = reviewsForItem(ws, itemId);
        var resultRows = results.findByWorkspaceIdAndItemIdOrderByRecordedAtDescIdDesc(ws, itemId);
        var practiceRows = practices.findByWorkspaceIdAndKind(ws, new ItemKind(item.kind()));
        // Each review's assignments, the versions they pinned and each review's reference are read once for the trace.
        Map<UUID, Map<Long, ReviewPractice>> assignedByReview = new HashMap<>();
        if (!history.isEmpty())
            assignments
                    .findByWorkspaceIdAndReviewIdInOrderByReviewIdAscPracticeIdAsc(
                            ws, history.stream().map(Review::getId).toList())
                    .forEach(row -> assignedByReview
                            .computeIfAbsent(row.getReviewId(), ignored -> new HashMap<>())
                            .put(row.getPracticeId(), row));
        var versionIds = assignedByReview.values().stream()
                .flatMap(byPractice -> byPractice.values().stream())
                .map(ReviewPractice::getPracticeVersionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, PracticeVersion> versionById = versionIds.isEmpty()
                ? Map.of()
                : versions.findByWorkspaceIdAndIdIn(ws, versionIds).stream()
                        .collect(Collectors.toMap(
                                version -> Objects.requireNonNull(version.getId()), Function.identity()));
        var refs = reviewQuery.refs(ws, history);
        var defaultMode = defaults.forWorkspace(ws).defaultFeedbackMode();
        var trace = practiceRows.stream()
                .map(p -> {
                    Review review = history.stream()
                            .filter(r -> assignedByReview
                                    .getOrDefault(r.getId(), Map.of())
                                    .containsKey(p.getId()))
                            .findFirst()
                            .orElse(null);
                    var assigned = review == null
                            ? null
                            : assignedByReview
                                    .getOrDefault(review.getId(), Map.of())
                                    .get(p.getId());
                    var version = assigned == null || assigned.getPracticeVersionId() == null
                            ? null
                            : versionById.get(assigned.getPracticeVersionId());
                    if (review != null && assigned == null) return null;
                    var raw = version == null && review == null
                            ? p.getCurrentVersion().getDefinition()
                            : version == null ? null : version.getDefinition();
                    boolean known = raw != null
                            && raw.has("about")
                            && raw.path("reviewOn").isObject();
                    var reviewOn = raw != null && raw.path("reviewOn").isObject()
                            ? json.convertValue(raw.path("reviewOn"), ReviewOn.class)
                            : new ReviewOn(List.of(), Map.of());
                    var about = raw != null && raw.path("about").isString()
                            ? json.convertValue(raw.path("about"), AboutRole.class)
                            : null;
                    return PracticeTraceDeriver.derive(new TraceInputs(
                            p.getSlug(),
                            reviewOn,
                            about,
                            known,
                            FeedbackModeResolver.effectiveModeOf(p, defaultMode),
                            review == null ? null : refs.get(review.getId()),
                            assigned,
                            resultRows.stream()
                                    .filter(r -> r.getPracticeId() == p.getId()
                                            && (review == null
                                                    || r.getReviewId().equals(review.getId())))
                                    .toList()));
                })
                .filter(Objects::nonNull)
                .toList();
        return new ItemTraceDTO(
                item,
                events(ws, itemId, PageRequest.ofSize(100)).getContent(),
                history.stream()
                        .map(r -> Objects.requireNonNull(refs.get(r.getId())))
                        .toList(),
                trace);
    }

    public ItemRefDTO requireItem(long ws, long itemId) {
        var item = references.items(ws, List.of(itemId)).get(itemId);
        if (item == null) throw new EntityNotFoundException("Item", Long.toString(itemId));
        return item;
    }

    public List<Review> reviewsForItem(long ws, long itemId) {
        return ledger.reviewsForItem(ws, itemId);
    }

    public Page<ItemEventDTO> events(long ws, long itemId, Pageable page) {
        requireItem(ws, itemId);
        return ledger.events(ws, itemId, page)
                .map(row -> new ItemEventDTO(
                        row.getId(),
                        Long.toString(itemId),
                        row.getName(),
                        row.getItemVersion(),
                        row.getOccurredAt(),
                        row.getFoundBy(),
                        row.getState(),
                        row.getReason(),
                        row.getDecidedAt(),
                        row.getReviewId()));
    }
}
