package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.profile.dto.OverviewWindowDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.PracticeProfileOverviewDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewRefDTO;
import de.tum.cit.aet.hephaestus.practices.result.PracticeStandingService;
import de.tum.cit.aet.hephaestus.practices.result.ResultSourceAuthorization;
import de.tum.cit.aet.hephaestus.practices.result.ResultView;
import de.tum.cit.aet.hephaestus.practices.result.ResultViewQuery;
import de.tum.cit.aet.hephaestus.practices.result.ResultVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.review.MemberReviewReadinessService;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewProgressService;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PracticeProfileOverviewService {
    private final CurrentDeveloperLookup currentDeveloperLookup;
    private final ResultViewQuery views;
    private final ResultVisibilityPolicy visibility;
    private final Clock clock;
    private final ProfileChangeQuery profileChanges;
    private final PracticeStandingService standings;
    private final MemberReviewReadinessService readiness;
    private final ProfileOverviewRepository overview;
    private final ResultSourceAuthorization sources;
    private final ProductReferenceQuery references;
    private final ReviewProgressService progress;

    public PracticeProfileOverviewDTO getOverview(WorkspaceContext ws) {
        return visibility.asOneRead(ws.id(), () -> readOverview(ws));
    }

    private PracticeProfileOverviewDTO readOverview(WorkspaceContext ws) {
        var now = clock.instant();
        var account = currentDeveloperLookup.currentDeveloperId();
        if (account.isEmpty()) {
            var empty = OverviewWindow.sincePreviousRun(null, now);
            return new PracticeProfileOverviewDTO(
                    new OverviewWindowDTO(empty.since(), empty.until()), null, List.of(), List.of(), null, null);
        }
        // One read of the look-back serves the page and its changes, which reach no further back.
        var history = standings.history(ws.id(), account.get(), now.minus(OverviewWindow.LOOKBACK));
        var projected = history.permittedViews();
        var newest = new LinkedHashMap<UUID, ResultView>();
        projected.forEach(v -> newest.putIfAbsent(v.result().getReviewId(), v));
        var newestReviews = List.copyOf(newest.values());
        var latest = views.latestForDeveloper(ws.id(), account.get()).orElse(null);
        var window = OverviewWindow.sincePreviousRun(
                newestReviews.size() < 2 ? null : newestReviews.get(1).result().getRecordedAt(), now);
        var changes = profileChanges.changes(ws.id(), account.get(), window, history);
        var reviewed = projected.stream()
                .filter(v -> window.contains(v.result().getRecordedAt()))
                .map(ResultView::item)
                .distinct()
                .toList();
        return new PracticeProfileOverviewDTO(
                new OverviewWindowDTO(window.since(), window.until()),
                latest == null ? null : latestRef(ws.id(), latest),
                changes,
                reviewed,
                readiness.readiness(ws.id(), account.get()),
                activeReview(ws.id(), account.get()));
    }

    private ProfileReviewRefDTO latestRef(long ws, ResultView latest) {
        var review = latest.review();
        var status = review.getStatus();
        return new ProfileReviewRefDTO(
                review.getId(),
                latest.result().getRecordedAt(),
                latest.item(),
                status,
                progress.progress(ws, Map.of(review.getId(), status)).get(review.getId()));
    }

    // Behind the source fence a result passes: a review in flight has no result, so its work item alone is checked.
    private @Nullable ProfileReviewRefDTO activeReview(long ws, long account) {
        var active = overview.activeReview(ws, account).orElse(null);
        if (active == null || !sources.permitsItem(ws, active.getItemId(), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
            return null;
        var item = references.items(ws, List.of(active.getItemId())).get(active.getItemId());
        if (item == null) return null;
        var status = ReviewStatus.valueOf(active.getStatus());
        return new ProfileReviewRefDTO(
                active.getReviewId(),
                active.getOrderedAt(),
                item,
                status,
                progress.progress(ws, Map.of(active.getReviewId(), status)).get(active.getReviewId()));
    }
}
