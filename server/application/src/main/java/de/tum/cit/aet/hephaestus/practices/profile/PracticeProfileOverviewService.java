package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.profile.dto.OverviewWindowDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.PracticeProfileOverviewDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewRefDTO;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultView;
import de.tum.cit.aet.hephaestus.practices.result.ResultViewQuery;
import de.tum.cit.aet.hephaestus.practices.result.ResultVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewShownStatus;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PracticeProfileOverviewService {
    private final CurrentDeveloperLookup currentDeveloperLookup;
    private final ResultRepository read;
    private final ResultViewQuery views;
    private final ResultVisibilityPolicy visibility;
    private final Clock clock;
    private final ProfileChangeQuery profileChanges;
    private final ReviewRuntimeQuery runtime;

    public PracticeProfileOverviewDTO getOverview(WorkspaceContext ws) {
        return visibility.asOneRead(ws.id(), () -> readOverview(ws));
    }

    private PracticeProfileOverviewDTO readOverview(WorkspaceContext ws) {
        var now = clock.instant();
        var account = currentDeveloperLookup.currentDeveloperId();
        if (account.isEmpty()) {
            var empty = OverviewWindow.sincePreviousRun(null, now);
            return new PracticeProfileOverviewDTO(
                    new OverviewWindowDTO(empty.since(), empty.until()), null, List.of(), List.of());
        }
        var rows = read.forDevelopersSince(ws.id(), List.of(account.get()), now.minus(OverviewWindow.LOOKBACK), false);
        var permitted = visibility.permitsHistory(ws.id(), rows, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        var projected = views.views(
                ws.id(),
                rows.stream().filter(r -> permitted.contains(r.getId())).toList());
        var newest = new LinkedHashMap<UUID, ResultView>();
        projected.forEach(v -> newest.putIfAbsent(v.result().getReviewId(), v));
        var newestReviews = List.copyOf(newest.values());
        var latest = read.findFirstByWorkspaceIdAndDeveloperIdOrderByRecordedAtDescIdDesc(ws.id(), account.get())
                .map(result -> views.views(ws.id(), List.of(result)).getFirst())
                .orElse(null);
        var window = OverviewWindow.sincePreviousRun(
                newestReviews.size() < 2 ? null : newestReviews.get(1).result().getRecordedAt(), now);
        var changes = profileChanges.changes(ws.id(), account.get(), window);
        var reviewed = projected.stream()
                .filter(v -> window.contains(v.result().getRecordedAt()))
                .map(ResultView::item)
                .distinct()
                .toList();
        return new PracticeProfileOverviewDTO(
                new OverviewWindowDTO(window.since(), window.until()),
                latest == null
                        ? null
                        : new ProfileReviewRefDTO(
                                latest.review().getId(),
                                latest.result().getRecordedAt(),
                                latest.item(),
                                ReviewShownStatus.of(
                                        latest.review().getStatus(),
                                        runtime.jobCounts(
                                                        ws.id(),
                                                        List.of(latest.review().getId()))
                                                .getOrDefault(latest.review().getId(), Map.of()))),
                changes,
                reviewed);
    }
}
