package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.page.PracticePageFeedbackBasis;
import de.tum.cit.aet.hephaestus.practices.result.ResultView;
import de.tum.cit.aet.hephaestus.practices.result.ResultViewQuery;
import de.tum.cit.aet.hephaestus.practices.result.trend.WorkResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileFeedbackQuery {
    private final FeedbackRepository feedbackRepository;
    private final FeedbackDecisionRepository decisions;
    private final PracticePageFeedbackBasis feedbackEvidence;
    private final ResultViewQuery views;
    private final ProfileOverviewRepository ledger;

    public record Facts(
            List<Feedback> recent,
            Map<UUID, Instant> reviewedAtByRun,
            Map<UUID, List<ResultView>> evidenceByFeedback,
            Map<UUID, WorkResolution> resolutionByFeedback,
            Map<UUID, WorkResolution> resolutionBeforeByFeedback,
            Map<UUID, Instant> addressedAt,
            Map<UUID, Instant> practiceChangedAt) {}

    public Facts find(long ws, long account, Instant now, OverviewWindow window) {
        var readable = feedbackRepository.findReadablePracticePage(ws, List.of(account)).stream()
                .filter(f -> !f.getCreatedAt().isBefore(now.minus(OverviewWindow.LOOKBACK)))
                .toList();
        var withdrawn = readable.isEmpty()
                ? Set.<UUID>of()
                : decisions
                        .findCurrentVisibilityForFeedbackIds(
                                ws, readable.stream().map(Feedback::getId).toList())
                        .stream()
                        .filter(d -> d.getAction() == FeedbackDecisionAction.WITHDRAW)
                        .map(d -> d.getFeedbackId())
                        .collect(Collectors.toSet());
        var recent =
                readable.stream().filter(f -> !withdrawn.contains(f.getId())).toList();
        var ids = recent.stream().map(Feedback::getId).toList();
        var raw = feedbackEvidence.visibleEvidence(ws, ids);
        var changed = feedbackEvidence.practiceChangedAt(raw);
        var viewById = new HashMap<UUID, ResultView>();
        views.views(ws, raw.values().stream().flatMap(List::stream).toList())
                .forEach(view -> viewById.putIfAbsent(view.result().getId(), view));
        var projected = new LinkedHashMap<UUID, List<ResultView>>();
        raw.forEach((id, r) -> projected.put(
                id,
                r.stream()
                        .map(row -> Objects.requireNonNull(viewById.get(row.getId())))
                        .toList()));
        Map<UUID, Instant> addressed = ids.isEmpty()
                ? Map.of()
                : ledger.addressed(ws, ids, window.until()).stream()
                        .collect(Collectors.toMap(
                                ProfileOverviewRepository.AddressedFeedback::getFeedbackId,
                                ProfileOverviewRepository.AddressedFeedback::getCreatedAt));
        var at = new LinkedHashMap<UUID, Instant>();
        recent.stream()
                .filter(f -> window.contains(f.getCreatedAt()))
                .forEach(f -> at.put(f.getReviewId(), f.getCreatedAt()));
        return new Facts(
                recent,
                at,
                projected,
                feedbackEvidence.workResolutions(ws, recent, raw, changed, window.until()),
                feedbackEvidence.workResolutions(ws, recent, raw, changed, window.since()),
                addressed,
                changed);
    }
}
