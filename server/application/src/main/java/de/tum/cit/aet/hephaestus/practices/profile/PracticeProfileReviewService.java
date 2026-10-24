package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
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
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.trace.OwnItemTraceQueryService;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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

    private static final Comparator<List<ResultView>> OLDEST_FIRST = Comparator.comparing(
                    (List<ResultView> v) -> v.getFirst().review().getCreatedAt())
            .thenComparing(v -> v.getFirst().review().getId().toString());

    public Page<ProfileReviewDTO> list(
            WorkspaceContext ws,
            @Nullable String itemKind,
            @Nullable Long itemId,
            @Nullable String groupSlug,
            @Nullable Instant since,
            Pageable page) {
        return list(ws, itemKind == null ? null : List.of(itemKind), itemId, groupSlug, null, since, false, page);
    }

    /**
     * The reader's reviews, narrowed by kinds of work, one work, a group and statuses, newest first or
     * oldest first. Every narrowing applies before paging, so a page never comes back short of
     * reviews that a later page would have had.
     */
    public Page<ProfileReviewDTO> list(
            WorkspaceContext ws,
            @Nullable Collection<String> itemKinds,
            @Nullable Long itemId,
            @Nullable String groupSlug,
            @Nullable Collection<ReviewStatus> statuses,
            @Nullable Instant since,
            boolean oldestFirst,
            Pageable page) {
        var account = developer.currentDeveloperId();
        if (account.isEmpty()) return Page.empty(page);
        var rows = results.forDevelopersSince(
                ws.id(), List.of(account.get()), since == null ? Instant.EPOCH : since, false);
        var permitted = authorization.permitsAll(ws.id(), rows, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        boolean includeReviewChildren = itemId != null
                && items.items(ws.id(), List.of(itemId)).stream()
                        .anyMatch(item -> item.getKind().equals("scm.pull_request"));
        var byReview = new LinkedHashMap<UUID, List<ResultView>>();
        views.views(ws.id(), rows).stream()
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
                .sorted(oldestFirst ? OLDEST_FIRST : OLDEST_FIRST.reversed())
                .toList();
        return new PageImpl<>(
                ordered.stream()
                        .skip(page.getOffset())
                        .limit(page.getPageSize())
                        .map(v -> dto(v.getFirst().review(), ws.id(), v, permitted))
                        .toList(),
                page,
                ordered.size());
    }

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
        var dto = dto(review, ws.id(), projected, permitted);
        var counts = ReviewSummaryQueryService.counts(rows);
        return new ProfileReviewDetailDTO(
                dto.review(),
                dto.results(),
                dto.feedback(),
                trace,
                new OutcomeVectorDTO(counts.met(), counts.notMet(), counts.doesNotApply(), counts.couldNotDetermine()));
    }

    private ProfileReviewDTO dto(Review review, long workspaceId, List<ResultView> v, Set<UUID> permitted) {
        return new ProfileReviewDTO(
                reviews.ref(review),
                presentation.lists(workspaceId, v, permitted),
                v.stream()
                        .flatMap(r -> presentation.ownFeedback(r.result()).stream())
                        .distinct()
                        .toList());
    }
}
