package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.page.FeedbackClosure;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileChangeDTO;
import de.tum.cit.aet.hephaestus.practices.result.PracticeGroupStandingService;
import de.tum.cit.aet.hephaestus.practices.result.PracticeStandingService;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultView;
import de.tum.cit.aet.hephaestus.practices.result.ResultViewQuery;
import de.tum.cit.aet.hephaestus.practices.result.ResultVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.result.trend.WorkResolution;
import de.tum.cit.aet.hephaestus.practices.result.trend.WorkResolution.Work;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ProfileChangeResolvedBy;
import de.tum.cit.aet.hephaestus.practices.spi.ProfileChangeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What changed on one developer's Practice profile in one window, newest first. The Practice profile overview and
 * the weekly digest both read their changes here, so one rule decides what a change is.
 *
 * <p>Standing, trend, first-result and group changes come from {@link ProfileChangeDetector}. The {@code FEEDBACK_*}
 * changes come from Practice page feedback. A change about a practice that is no longer shown is dropped.
 */
@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileChangeQuery {
    private final PracticeStandingService practiceStandingService;
    private final PracticeGroupStandingService practiceGroupStandingService;
    private final PracticeGroupRepository groups;
    private final ResultRepository read;
    private final ResultViewQuery views;
    private final ResultVisibilityPolicy visibility;
    private final ProductReferenceQuery references;
    private final ProfileFeedbackQuery feedbackFacts;
    private final ProfileOverviewRepository ledger;

    public List<ProfileChangeDTO> changes(long workspaceId, long accountId, OverviewWindow window) {
        return visibility.asOneRead(workspaceId, () -> readChanges(workspaceId, accountId, window));
    }

    private List<ProfileChangeDTO> readChanges(long workspaceId, long accountId, OverviewWindow window) {
        var rows = read.forDevelopersSince(workspaceId, List.of(accountId), window.since(), false).stream()
                .filter(r -> window.contains(r.getRecordedAt()))
                .toList();
        var permitted = visibility.permitsHistory(workspaceId, rows, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        var projected = views.views(
                workspaceId,
                rows.stream().filter(r -> permitted.contains(r.getId())).toList());
        var snapshots = practiceStandingService.getStandingSnapshots(
                workspaceId, accountId, List.of(window.since(), window.until()));
        var before = snapshots.getFirst();
        var after = snapshots.getLast();
        var shown = groups.findByWorkspaceIdAndShownToDevelopersTrueOrderByPositionAscNameAsc(workspaceId);
        Map<String, List<Work>> windowWork = new LinkedHashMap<>();
        projected.stream()
                .collect(Collectors.groupingBy(v -> v.practice().getSlug()))
                .forEach((slug, v) ->
                        windowWork.put(slug, Work.newestFirst(v.stream().map(Work::of))));
        var first = ledger.firstOpportunities(workspaceId, accountId).stream()
                .collect(Collectors.toMap(
                        ProfileOverviewRepository.FirstOpportunity::getSlug,
                        ProfileOverviewRepository.FirstOpportunity::getRecordedAt));
        var feedback = feedbackFacts.find(workspaceId, accountId, window.until(), window);
        var cited = new LinkedHashSet<Long>();
        projected.forEach(v -> cited.add(v.result().getItemId()));
        feedback.evidenceByFeedback()
                .values()
                .forEach(v -> v.forEach(r -> cited.add(r.result().getItemId())));
        Stream.concat(
                        feedback.resolutionByFeedback().values().stream(),
                        feedback.resolutionBeforeByFeedback().values().stream())
                .forEach(r -> {
                    r.cleanWork().forEach(w -> cited.add(w.itemId()));
                    r.problemWork().forEach(w -> cited.add(w.itemId()));
                });
        var items = references.items(workspaceId, cited);
        var changes = new ArrayList<>(ProfileChangeDetector.detect(
                window,
                before.dtos(),
                after.dtos(),
                practiceGroupStandingService.summarize(shown, before),
                practiceGroupStandingService.summarize(shown, after),
                windowWork,
                first,
                items));
        changes.addAll(feedbackChanges(feedback, window, items));
        changes.removeIf(c -> c.practiceSlug() != null && !after.practices().containsKey(c.practiceSlug()));
        changes.sort(Comparator.comparing(ProfileChangeDTO::at).reversed());
        var seen = new HashSet<String>();
        return changes.stream()
                .filter(c -> c.feedbackId() == null || seen.add(c.practiceSlug() + ":" + c.type()))
                .toList();
    }

    private static List<ProfileChangeDTO> feedbackChanges(
            ProfileFeedbackQuery.Facts facts, OverviewWindow window, Map<Long, ItemRefDTO> targets) {
        List<ProfileChangeDTO> changes = new ArrayList<>();
        for (Feedback feedback : facts.recent()) {
            Instant preparedByRunAt = facts.reviewedAtByRun().get(feedback.getReviewId());
            List<ResultView> evidence = facts.evidenceByFeedback().get(feedback.getId());
            if (preparedByRunAt != null && evidence != null) {
                changes.add(feedbackChange(
                        ProfileChangeType.FEEDBACK_NEW, preparedByRunAt, feedback.getId(), null, evidence, targets));
            }
        }
        for (Feedback feedback : facts.recent()) {
            ProfileChangeDTO reset = feedbackReset(facts, feedback, window, targets);
            if (reset != null) {
                changes.add(reset);
            }
        }
        Set<UUID> answered = new LinkedHashSet<>(facts.resolutionByFeedback().keySet());
        answered.addAll(facts.addressedAt().keySet());
        for (UUID feedbackId : answered) {
            ProfileChangeDTO resolved = feedbackResolved(facts, feedbackId, window, targets);
            if (resolved != null) {
                changes.add(resolved);
            }
        }
        return changes;
    }

    /**
     * Open feedback the developer's work had started to answer and has now fallen back on: its clean run
     * stood somewhere at the window's lower edge and stands at nothing at its upper edge, because work
     * reviewed inside the window raised the problem again. Feedback already closed ({@link FeedbackClosure})
     * cannot fall back: what the work says about it afterwards is new feedback's business, exactly as
     * {@link WorkResolution} puts it.
     */
    private static @Nullable ProfileChangeDTO feedbackReset(
            ProfileFeedbackQuery.Facts facts, Feedback feedback, OverviewWindow window, Map<Long, ItemRefDTO> targets) {
        UUID feedbackId = feedback.getId();
        WorkResolution atTheEnd = facts.resolutionByFeedback().get(feedbackId);
        WorkResolution atTheStart = facts.resolutionBeforeByFeedback().get(feedbackId);
        List<ResultView> evidence = facts.evidenceByFeedback().get(feedbackId);
        if (atTheEnd == null
                || atTheStart == null
                || evidence == null
                || FeedbackClosure.of(
                                atTheEnd.resolvedAt(),
                                facts.addressedAt().get(feedbackId),
                                facts.practiceChangedAt().get(feedbackId))
                        != null
                || atTheStart.cleanWork().isEmpty()
                || !atTheEnd.cleanWork().isEmpty()) {
            return null;
        }
        List<Work> problems = atTheEnd.problemWork().stream()
                .filter(work -> window.contains(work.at()))
                .toList();
        if (problems.isEmpty()) {
            return null;
        }
        return feedbackChange(
                ProfileChangeType.FEEDBACK_RESET,
                problems.getFirst().at(),
                feedbackId,
                null,
                WorkResolution.CLEAN_NEEDED,
                evidence.getFirst().practice(),
                ProfileChangeDetector.refs(problems, targets));
    }

    /** The resolution its card reports, when it falls inside the window. */
    private static @Nullable ProfileChangeDTO feedbackResolved(
            ProfileFeedbackQuery.Facts facts, UUID feedbackId, OverviewWindow window, Map<Long, ItemRefDTO> targets) {
        List<ResultView> evidence = facts.evidenceByFeedback().get(feedbackId);
        WorkResolution resolution = facts.resolutionByFeedback().getOrDefault(feedbackId, WorkResolution.NONE);
        FeedbackClosure closure = FeedbackClosure.of(
                resolution.resolvedAt(),
                facts.addressedAt().get(feedbackId),
                facts.practiceChangedAt().get(feedbackId));
        if (evidence == null || closure == null || !window.contains(closure.at())) {
            return null;
        }
        return switch (closure.by()) {
            case WORK ->
                feedbackChange(
                        ProfileChangeType.FEEDBACK_RESOLVED,
                        closure.at(),
                        feedbackId,
                        ProfileChangeResolvedBy.WORK,
                        null,
                        evidence.getFirst().practice(),
                        ProfileChangeDetector.refs(Work.newestFirst(resolution.cleanWork().stream()), targets));
            case DEVELOPER ->
                feedbackChange(
                        ProfileChangeType.FEEDBACK_RESOLVED,
                        closure.at(),
                        feedbackId,
                        ProfileChangeResolvedBy.DEVELOPER,
                        evidence,
                        targets);
            case PRACTICE_CHANGED -> null;
        };
    }

    /** A change citing the feedback's own evidence, newest first. */
    private static ProfileChangeDTO feedbackChange(
            ProfileChangeType type,
            Instant at,
            UUID feedbackId,
            @Nullable ProfileChangeResolvedBy resolvedBy,
            List<ResultView> evidence,
            Map<Long, ItemRefDTO> targets) {
        return feedbackChange(
                type,
                at,
                feedbackId,
                resolvedBy,
                null,
                evidence.getFirst().practice(),
                ProfileChangeDetector.refs(Work.newestFirst(evidence.stream().map(Work::of)), targets));
    }

    /** The practice is the one the feedback is about, read off its newest evidence. */
    private static ProfileChangeDTO feedbackChange(
            ProfileChangeType type,
            Instant at,
            UUID feedbackId,
            @Nullable ProfileChangeResolvedBy resolvedBy,
            @Nullable Integer cleanNeeded,
            Practice practice,
            List<ItemRefDTO> work) {
        PracticeGroup group = practice.getGroup();
        return new ProfileChangeDTO(
                type,
                at,
                practice.getSlug(),
                Objects.requireNonNull(practice.getCurrentVersion().getName()),
                group == null ? null : group.getSlug(),
                group == null ? null : group.getName(),
                null,
                null,
                null,
                feedbackId,
                resolvedBy,
                cleanNeeded,
                work);
    }
}
