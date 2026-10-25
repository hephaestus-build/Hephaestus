package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.OwnFeedbackRefDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewDetailDTO;
import de.tum.cit.aet.hephaestus.practices.result.ResultPresentation;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultView;
import de.tum.cit.aet.hephaestus.practices.result.ResultViewQuery;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewSummaryQueryService;
import de.tum.cit.aet.hephaestus.practices.review.dto.OutcomeVectorDTO;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.trace.OwnItemTraceQueryService;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PracticeProfileReviewService {
    private final CurrentDeveloperLookup developer;
    private final ResultRepository results;
    private final ResultViewQuery views;
    private final ResultPresentation presentation;
    private final ReviewSummaryQueryService reviews;
    private final OwnItemTraceQueryService traces;
    private final EvidenceAuthorization authorization;
    private final ProductItemRepository items;

    private static final Comparator<Review> REVIEW_OLDEST_FIRST = Comparator.comparing(Review::getCreatedAt)
            .thenComparing(review -> review.getId().toString());

    private static final Comparator<List<ResultView>> OLDEST_FIRST =
            Comparator.comparing(v -> v.getFirst().review(), REVIEW_OLDEST_FIRST);

    public Page<ProfileReviewDTO> list(
            WorkspaceContext ws,
            @Nullable String itemKind,
            @Nullable Long itemId,
            @Nullable String groupSlug,
            @Nullable Instant since,
            Pageable page) {
        return list(
                ws,
                new OwnReviewFilter(itemKind == null ? null : List.of(itemKind), itemId, groupSlug, null, since, false),
                page);
    }

    /**
     * The reader's reviews, narrowed by kinds of work, one work, a group, statuses and a start time,
     * newest first or oldest first. Every narrowing applies before paging, so a page never comes back
     * short of reviews that a later page would have had. Each review's place on its work counts every
     * review of the work, so a narrowed list still says which review is a work's latest.
     */
    public Page<ProfileReviewDTO> list(WorkspaceContext ws, OwnReviewFilter filter, Pageable page) {
        var itemKinds = filter.itemKinds();
        var itemId = filter.itemId();
        var groupSlug = filter.groupSlug();
        var statuses = filter.statuses();
        var since = filter.since();
        var account = developer.currentDeveloperId();
        if (account.isEmpty()) return Page.empty(page);
        var rows = results.forDevelopersSince(ws.id(), List.of(account.get()), Instant.EPOCH, false);
        var permitted = authorization.permitsAll(ws.id(), rows, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        var all = views.views(ws.id(), rows);
        var places = placesOnWork(all);
        boolean includeReviewChildren = itemId != null
                && items.items(ws.id(), List.of(itemId)).stream()
                        .anyMatch(item -> item.getKind().equals("scm.pull_request"));
        var byReview = new LinkedHashMap<UUID, List<ResultView>>();
        all.stream()
                .filter(v -> since == null || !v.result().getRecordedAt().isBefore(since))
                .filter(v -> itemKinds == null
                        || itemKinds.isEmpty()
                        || itemKinds.contains(v.item().kind()))
                .filter(v -> itemId == null
                        || v.result().getItemId() == itemId
                        || (includeReviewChildren
                                && v.item().kind().equals("scm.pull_request_review")
                                && Long.toString(itemId).equals(v.item().parentId())))
                .filter(v -> groupSlug == null
                        || (v.practice().getGroup() != null
                                && v.practice().getGroup().getSlug().equals(groupSlug)))
                .forEach(v -> byReview.computeIfAbsent(v.result().getReviewId(), id -> new ArrayList<>())
                        .add(v));
        var ordered = byReview.values().stream()
                .filter(v -> statuses == null
                        || statuses.isEmpty()
                        || statuses.contains(v.getFirst().review().getStatus()))
                .sorted(filter.oldestFirst() ? OLDEST_FIRST : OLDEST_FIRST.reversed())
                .toList();
        return new PageImpl<>(
                ordered.stream()
                        .skip(page.getOffset())
                        .limit(page.getPageSize())
                        .map(v -> {
                            var review = v.getFirst().review();
                            var place = Objects.requireNonNull(places.get(review.getId()));
                            return new ProfileReviewDTO(
                                    reviews.ref(review),
                                    presentation.lists(ws.id(), v, permitted),
                                    feedback(v),
                                    place.position(),
                                    place.total());
                        })
                        .toList(),
                page,
                ordered.size());
    }

    /** Each review's place among the reader's reviews of its work, oldest first, by review id. */
    private static Map<UUID, Place> placesOnWork(List<ResultView> all) {
        var reviewsByWork = new LinkedHashMap<Long, Map<UUID, Review>>();
        for (var v : all) {
            reviewsByWork
                    .computeIfAbsent(v.review().getItemId(), item -> new LinkedHashMap<>())
                    .putIfAbsent(v.review().getId(), v.review());
        }
        var places = new HashMap<UUID, Place>();
        for (var onWork : reviewsByWork.values()) {
            var oldestFirst =
                    onWork.values().stream().sorted(REVIEW_OLDEST_FIRST).toList();
            for (int i = 0; i < oldestFirst.size(); i++) {
                places.put(oldestFirst.get(i).getId(), new Place(i + 1, oldestFirst.size()));
            }
        }
        return places;
    }

    private record Place(int position, int total) {}

    public ProfileReviewDetailDTO get(WorkspaceContext ws, UUID id) {
        long account = developer.currentDeveloperIdElseThrow();
        var review = reviews.require(ws.id(), id);
        var rows = results.findByWorkspaceIdAndReviewIdOrderByRecordedAtAscIdAsc(ws.id(), id).stream()
                .filter(r -> Objects.equals(r.getDeveloperId(), account))
                .toList();
        var permitted = authorization.permitsAll(ws.id(), rows, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        var projected = views.views(ws.id(), rows);
        var trace = traces.own(
                ws.id(), rows.isEmpty() ? review.getItemId() : rows.getFirst().getItemId(), id);
        var counts = ReviewSummaryQueryService.counts(rows);
        return new ProfileReviewDetailDTO(
                reviews.ref(review),
                presentation.lists(ws.id(), projected, permitted),
                feedback(projected),
                trace,
                new OutcomeVectorDTO(counts.met(), counts.notMet(), counts.doesNotApply(), counts.couldNotDetermine()));
    }

    private List<OwnFeedbackRefDTO> feedback(List<ResultView> v) {
        return v.stream()
                .flatMap(r -> presentation.ownFeedback(r.result()).stream())
                .distinct()
                .toList();
    }
}
