package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackWithdrawalRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.inapp.FeedbackClosure;
import de.tum.cit.aet.hephaestus.practices.feedback.inapp.InAppFeedbackEvidence;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.DeveloperReviewRunRow;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.FirstObservedRow;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeGroupStandingService;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService.StandingSnapshot;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository.AddressedFeedbackProjection;
import de.tum.cit.aet.hephaestus.practices.observation.trend.TrendDirection;
import de.tum.cit.aet.hephaestus.practices.observation.trend.WorkResolution;
import de.tum.cit.aet.hephaestus.practices.observation.trend.WorkResolution.Work;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileChangeDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup.Target;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What changed on one developer's practice profile inside a window: the one home of the profile's change facts,
 * read by the practice profile overview and by the weekly digest.
 *
 * <p>The standings come from the one standing service, read as of the two edges of the window, so this class owns no
 * standing arithmetic: it compares labels. The evidence it attaches is the work reviewed inside the window for the
 * practice in question, which is exactly what the two snapshots differ by. The feedback changes read the developer's
 * readable in-app feedback, what their work has said about it since and their responses to it.
 */
@Component
@RequiredArgsConstructor
public class ProfileChangeDetector {

    private final PracticeStandingService practiceStandingService;
    private final PracticeGroupStandingService practiceGroupStandingService;
    private final PracticeGroupRepository practiceGroupRepository;
    private final ObservationRepository observationRepository;
    private final FeedbackRepository feedbackRepository;
    private final FeedbackWithdrawalRepository withdrawalRepository;
    private final InAppFeedbackEvidence feedbackEvidence;
    private final ReactionRepository reactionRepository;
    private final ReviewRunLookup reviewRunLookup;

    /**
     * The changes in a window, with what the caller needs to name the rest of its page.
     *
     * @param changes one change per subject and type, newest first
     * @param after the standings as of the window's upper edge
     * @param targets the runs behind the changes' work, the window's runs and {@code alsoNamed}, for naming them
     */
    public record WindowChanges(List<ProfileChangeDTO> changes, StandingSnapshot after, Map<UUID, Target> targets) {}

    /**
     * What changed for the developer inside {@code window}.
     *
     * @param windowRuns the developer's review runs inside the window; their feedback is new feedback
     * @param alsoNamed further runs the caller names on the same page, so one lookup names them all
     */
    @Transactional(readOnly = true)
    public WindowChanges detect(
            long workspaceId,
            long developerId,
            OverviewWindow window,
            List<DeveloperReviewRunRow> windowRuns,
            Collection<UUID> alsoNamed) {
        return detect(workspaceId, developerId, List.of(window), windowRuns, alsoNamed)
                .getFirst();
    }

    /**
     * What changed for the developer inside each window, without new feedback: no window run is passed, and new
     * feedback is what a window's runs composed. One load of the standings serves every window.
     */
    @Transactional(readOnly = true)
    public List<List<ProfileChangeDTO>> detectWithoutNewFeedback(
            long workspaceId, long developerId, List<OverviewWindow> windows) {
        if (windows.isEmpty()) {
            return List.of();
        }
        return detect(workspaceId, developerId, windows, List.of(), List.of()).stream()
                .map(WindowChanges::changes)
                .toList();
    }

    private List<WindowChanges> detect(
            long workspaceId,
            long developerId,
            List<OverviewWindow> windows,
            List<DeveloperReviewRunRow> windowRuns,
            Collection<UUID> alsoNamed) {
        List<StandingSnapshot> edges = practiceStandingService.getStandingSnapshots(
                workspaceId,
                developerId,
                windows.stream()
                        .flatMap(window -> Stream.of(window.since(), window.until()))
                        .toList());
        List<PracticeGroup> groups =
                practiceGroupRepository
                        .findByWorkspaceIdAndVisibleInPracticeDashboardsTrueOrderByDisplayOrderAscNameAsc(workspaceId);
        Map<String, Instant> firstObservedAt =
                observationRepository.findFirstObservedAtByPractice(developerId, workspaceId).stream()
                        .collect(Collectors.toMap(
                                FirstObservedRow::getPracticeSlug, FirstObservedRow::getFirstObservedAt));

        List<FeedbackFacts> feedback = readFeedback(workspaceId, developerId, windows, windowRuns, edges);
        List<WindowFacts> facts = new ArrayList<>(windows.size());
        for (int i = 0; i < windows.size(); i++) {
            OverviewWindow window = windows.get(i);
            StandingSnapshot after = edges.get(2 * i + 1);
            facts.add(new WindowFacts(
                    window, edges.get(2 * i), after, windowWorkByPractice(after, window), feedback.get(i)));
        }
        Map<UUID, Target> targets = reviewRunLookup.findTargets(
                workspaceId,
                Stream.concat(
                                Stream.concat(
                                        windowRuns.stream().map(DeveloperReviewRunRow::getJobId), alsoNamed.stream()),
                                facts.stream().flatMap(WindowFacts::namedJobIds))
                        .collect(Collectors.toSet()));

        return facts.stream()
                .map(window -> {
                    List<ProfileChangeDTO> changes = new ArrayList<>(standingChanges(
                            window.window(),
                            window.before().dtos(),
                            window.after().dtos(),
                            practiceGroupStandingService.summarize(groups, window.before()),
                            practiceGroupStandingService.summarize(groups, window.after()),
                            window.workByPractice(),
                            firstObservedAt,
                            targets));
                    changes.addAll(feedbackChanges(window.feedback(), window.window(), targets));
                    // A practice switched off for the workspace says nothing on the profile, its feedback included.
                    Set<String> reviewed = window.after().practices().keySet();
                    changes.removeIf(
                            change -> change.practiceSlug() != null && !reviewed.contains(change.practiceSlug()));
                    changes.sort(Comparator.comparing(ProfileChangeDTO::at).reversed());
                    return new WindowChanges(newestFeedbackChangePerPractice(changes), window.after(), targets);
                })
                .toList();
    }

    /** What one window reads before its runs are named. */
    private record WindowFacts(
            OverviewWindow window,
            StandingSnapshot before,
            StandingSnapshot after,
            Map<String, List<Work>> workByPractice,
            FeedbackFacts feedback) {

        /** The window's work, the clean work of its standings and what its feedback cites. */
        Stream<UUID> namedJobIds() {
            return Stream.of(
                            workByPractice.values().stream()
                                    .flatMap(List::stream)
                                    .map(Work::jobId),
                            after.practices().values().stream()
                                    .flatMap(practice -> practice.trend().cleanWork().jobIds().stream()),
                            feedback.citedJobIds())
                    .flatMap(ids -> ids);
        }
    }

    /**
     * Reads what moved between two standings snapshots of one developer. Pure: it only compares labels.
     *
     * @param before the practice standings as of the window's lower edge
     * @param after the practice standings as of its upper edge
     * @param groupsBefore the group standings as of the lower edge
     * @param groupsAfter the group standings as of the upper edge
     * @param windowWorkByPractice the work reviewed inside the window, per practice slug, newest first
     * @param firstObservedAt when each practice first recorded an observation about the developer
     * @param targets the runs behind the window's work, for naming it
     */
    static List<ProfileChangeDTO> standingChanges(
            OverviewWindow window,
            List<PracticeStandingDTO> before,
            List<PracticeStandingDTO> after,
            List<PracticeGroupStandingDTO> groupsBefore,
            List<PracticeGroupStandingDTO> groupsAfter,
            Map<String, List<Work>> windowWorkByPractice,
            Map<String, Instant> firstObservedAt,
            Map<UUID, Target> targets) {
        Map<String, PracticeStandingDTO> earlier = bySlug(before, PracticeStandingDTO::slug);
        Map<String, PracticeGroupStandingDTO> earlierGroups = bySlug(groupsBefore, PracticeGroupStandingDTO::groupSlug);
        List<ProfileChangeDTO> changes = new ArrayList<>();

        for (PracticeStandingDTO now : after) {
            List<Work> work = windowWorkByPractice.getOrDefault(now.slug(), List.of());
            List<ReviewedWorkRefDTO> evidence = refs(work, targets);
            Instant at = work.isEmpty() ? window.until() : work.getFirst().at();
            PracticeStandingDTO was = earlier.get(now.slug());
            PracticeStandingDTO.Standing standingBefore =
                    was == null ? PracticeStandingDTO.Standing.NOT_OBSERVED : was.standing();
            if (standingBefore != now.standing()
                    && (PracticeStandingDTO.isVerdict(standingBefore)
                            || PracticeStandingDTO.isVerdict(now.standing()))) {
                changes.add(practiceChange(
                        ProfileChangeDTO.Type.STANDING_MOVED,
                        at,
                        now,
                        standingBefore.name(),
                        now.standing().name(),
                        direction(standingBefore, now.standing()),
                        evidence));
            }
            TrendDirection directionBefore = was == null ? null : was.direction();
            TrendDirection direction = now.direction();
            // Only a turn one side of which points somewhere: crossing the minimum evidence into UNCERTAIN, or
            // falling back out of it, changes what the trend may claim, not where the developer's work is going.
            if (directionBefore != null
                    && direction != null
                    && directionBefore != direction
                    && (directionBefore.isDirectional() || direction.isDirectional())) {
                changes.add(practiceChange(
                        ProfileChangeDTO.Type.TREND_TURNED,
                        at,
                        now,
                        directionBefore.name(),
                        direction.name(),
                        null,
                        evidence));
            }
            Instant first = firstObservedAt.get(now.slug());
            if (first != null && window.contains(first)) {
                changes.add(
                        practiceChange(ProfileChangeDTO.Type.FIRST_OBSERVED, first, now, null, null, null, evidence));
            }
        }

        Map<String, List<Work>> windowWorkByGroup = windowWorkByGroup(after, windowWorkByPractice);
        for (PracticeGroupStandingDTO now : groupsAfter) {
            PracticeGroupStandingDTO was = earlierGroups.get(now.groupSlug());
            PracticeGroupStandingDTO.Standing standingBefore =
                    was == null ? PracticeGroupStandingDTO.Standing.NOT_OBSERVED : was.standing();
            if (standingBefore == now.standing()
                    || (!PracticeGroupStandingDTO.isVerdict(standingBefore)
                            && !PracticeGroupStandingDTO.isVerdict(now.standing()))) {
                continue;
            }
            List<Work> work = windowWorkByGroup.getOrDefault(now.groupSlug(), List.of());
            changes.add(new ProfileChangeDTO(
                    ProfileChangeDTO.Type.GROUP_MOVED,
                    work.isEmpty() ? window.until() : work.getFirst().at(),
                    null,
                    null,
                    now.groupSlug(),
                    now.groupName(),
                    standingBefore.name(),
                    now.standing().name(),
                    direction(
                            standingBefore.asPracticeStanding(), now.standing().asPracticeStanding()),
                    null,
                    null,
                    null,
                    refs(work, targets)));
        }
        return changes;
    }

    /** The work as the page names it, in the work's order. */
    static List<ReviewedWorkRefDTO> refs(List<Work> work, Map<UUID, Target> targets) {
        return work.stream()
                .map(ref -> ReviewedWorkLabels.ref(ref.kind(), ref.id(), targets.get(ref.jobId())))
                .toList();
    }

    /** The work the window's observations were on, per practice: one entry per piece of work, newest first. */
    private static Map<String, List<Work>> windowWorkByPractice(StandingSnapshot after, OverviewWindow window) {
        Map<String, List<Work>> byPractice = new LinkedHashMap<>();
        after.practices().forEach((slug, practice) -> {
            List<Work> work = Work.newestFirst(practice.evidence().stream()
                    .filter(observation -> window.contains(observation.getObservedAt()))
                    .map(Work::of));
            if (!work.isEmpty()) {
                byPractice.put(slug, work);
            }
        });
        return byPractice;
    }

    /**
     * What the look-back's in-app feedback says, read once: the readable feedback, the evidence it may still
     * show, how the developer's work has answered it, when the developer marked it addressed and when its
     * practice changed.
     *
     * @param reviewedAtByRun when each of the window's runs reviewed its work, by job id
     * @param addressedAt when the developer marked each piece of feedback addressed, by feedback id
     */
    private record FeedbackFacts(
            List<Feedback> recent,
            Map<UUID, Instant> reviewedAtByRun,
            Map<UUID, List<Observation>> evidenceByFeedback,
            Map<UUID, WorkResolution> resolutionByFeedback,
            Map<UUID, WorkResolution> resolutionBeforeByFeedback,
            Map<UUID, Instant> addressedAt,
            Map<UUID, Instant> practiceChangedAt) {
        /** Every run a feedback change can cite: the evidence's, the clean work's and the problem work's. */
        Stream<UUID> citedJobIds() {
            return Stream.of(
                            evidenceByFeedback.values().stream()
                                    .flatMap(List::stream)
                                    .map(Observation::getAgentJobId),
                            resolutionByFeedback.values().stream()
                                    .flatMap(resolution -> Stream.concat(
                                            resolution.cleanWork().stream(), resolution.problemWork().stream()))
                                    .map(Work::jobId))
                    .flatMap(ids -> ids);
        }
    }

    /**
     * For each window, the feedback prepared within the standing's look-back before the window's upper edge
     * ({@code docs/contributor/practice-review-glossary.mdx} § Practice profile overview). The resolution is read for
     * every piece: a resolution before the window makes a later answer inside it no change. One read serves every
     * window.
     *
     * @param edges the standings at each window's lower and upper edge, in window order
     */
    private List<FeedbackFacts> readFeedback(
            long workspaceId,
            long developerId,
            List<OverviewWindow> windows,
            List<DeveloperReviewRunRow> windowRuns,
            List<StandingSnapshot> edges) {
        Instant earliest = windows.stream()
                .map(window -> window.until().minus(OverviewWindow.LOOKBACK))
                .min(Comparator.naturalOrder())
                .orElseThrow();
        Instant latest = windows.stream()
                .map(OverviewWindow::until)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        List<Feedback> readable = feedbackRepository.findReadableInAppPreparedSince(workspaceId, developerId, earliest);
        // Withdrawn guidance is neither news nor resolved: what it said was wrong, whatever the work did since.
        Set<UUID> withdrawn = withdrawalRepository.withdrawnAmong(
                workspaceId, readable.stream().map(Feedback::getId).toList());
        List<Feedback> standing = readable.stream()
                .filter(feedback -> !withdrawn.contains(feedback.getId()))
                .toList();
        // Responses from the feedback's start, not from the window's edge: feedback the developer marked
        // addressed before the window opened and whose work then resolved it inside the window resolved before
        // the window, once.
        List<AddressedFeedbackProjection> responses = reactionRepository.findInAppResolvedByDeveloperBetween(
                developerId, workspaceId, earliest, latest, FeedbackResolution.resolvingNames());
        Map<UUID, List<Observation>> allEvidence = feedbackEvidence.visibleEvidence(
                workspaceId, standing.stream().map(Feedback::getId).toList());
        Map<UUID, Instant> allPracticeChangedAt = feedbackEvidence.practiceChangedAt(allEvidence);
        Map<UUID, Instant> reviewedAtByRun = windowRuns.stream()
                .collect(Collectors.toMap(DeveloperReviewRunRow::getJobId, DeveloperReviewRunRow::getReviewedAt));

        List<FeedbackFacts> facts = new ArrayList<>(windows.size());
        for (int i = 0; i < windows.size(); i++) {
            OverviewWindow window = windows.get(i);
            Instant lookback = window.until().minus(OverviewWindow.LOOKBACK);
            List<Feedback> recent = standing.stream()
                    .filter(feedback -> !feedback.getCreatedAt().isBefore(lookback)
                            && !feedback.getCreatedAt().isAfter(window.until()))
                    .toList();
            Set<UUID> recentIds = recent.stream().map(Feedback::getId).collect(Collectors.toSet());
            Map<UUID, List<Observation>> evidenceByFeedback = restricted(allEvidence, recentIds);
            Map<UUID, Instant> practiceChangedAt = restricted(allPracticeChangedAt, recentIds);
            facts.add(new FeedbackFacts(
                    recent,
                    reviewedAtByRun,
                    evidenceByFeedback,
                    InAppFeedbackEvidence.workResolutionsFrom(
                            recent, evidenceByFeedback, practiceChangedAt, liveRunsByPractice(edges.get(2 * i + 1))),
                    InAppFeedbackEvidence.workResolutionsFrom(
                            recent, evidenceByFeedback, practiceChangedAt, liveRunsByPractice(edges.get(2 * i))),
                    responses.stream()
                            .filter(response -> recentIds.contains(response.getFeedbackId())
                                    && response.getRespondedAt().isAfter(lookback)
                                    && !response.getRespondedAt().isAfter(window.until()))
                            .collect(Collectors.toMap(
                                    AddressedFeedbackProjection::getFeedbackId,
                                    AddressedFeedbackProjection::getRespondedAt)),
                    practiceChangedAt));
        }
        return facts;
    }

    private static <T> Map<UUID, T> restricted(Map<UUID, T> byFeedback, Set<UUID> feedbackIds) {
        Map<UUID, T> kept = new LinkedHashMap<>();
        byFeedback.forEach((feedbackId, value) -> {
            if (feedbackIds.contains(feedbackId)) {
                kept.put(feedbackId, value);
            }
        });
        return kept;
    }

    /**
     * New, reset and resolved in-app feedback inside the window. New is what the window's runs composed, dated by
     * its run rather than by its own timestamp, which is written moments after the run's newest observation and
     * can fall past the window's upper edge. Resolved is feedback whose {@link FeedbackClosure} falls inside the
     * window and is a resolution, by the work or by the developer, whichever run had said it; it is the closure
     * its card reports, since only feedback prepared inside the look-back is read
     * ({@code docs/contributor/practice-review-glossary.mdx} § Practice profile overview). Feedback its
     * practice's change closed first was not resolved, and a later answer does not make it so.
     */
    private static List<ProfileChangeDTO> feedbackChanges(
            FeedbackFacts facts, OverviewWindow window, Map<UUID, Target> targets) {
        List<ProfileChangeDTO> changes = new ArrayList<>();
        for (Feedback feedback : facts.recent()) {
            Instant preparedByRunAt = facts.reviewedAtByRun().get(feedback.getAgentJobId());
            List<Observation> evidence = facts.evidenceByFeedback().get(feedback.getId());
            if (preparedByRunAt != null && evidence != null) {
                changes.add(feedbackChange(
                        ProfileChangeDTO.Type.FEEDBACK_NEW,
                        preparedByRunAt,
                        feedback.getId(),
                        null,
                        evidence,
                        targets));
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
            FeedbackFacts facts, Feedback feedback, OverviewWindow window, Map<UUID, Target> targets) {
        UUID feedbackId = feedback.getId();
        WorkResolution atTheEnd = facts.resolutionByFeedback().get(feedbackId);
        WorkResolution atTheStart = facts.resolutionBeforeByFeedback().get(feedbackId);
        List<Observation> evidence = facts.evidenceByFeedback().get(feedbackId);
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
                ProfileChangeDTO.Type.FEEDBACK_RESET,
                problems.getFirst().at(),
                feedbackId,
                null,
                WorkResolution.CLEAN_NEEDED,
                evidence.getFirst().getPractice(),
                refs(problems, targets));
    }

    /** The resolution its card reports, when it falls inside the window. */
    private static @Nullable ProfileChangeDTO feedbackResolved(
            FeedbackFacts facts, UUID feedbackId, OverviewWindow window, Map<UUID, Target> targets) {
        List<Observation> evidence = facts.evidenceByFeedback().get(feedbackId);
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
                        ProfileChangeDTO.Type.FEEDBACK_RESOLVED,
                        closure.at(),
                        feedbackId,
                        ProfileChangeDTO.ResolvedBy.WORK,
                        null,
                        evidence.getFirst().getPractice(),
                        refs(Work.newestFirst(resolution.cleanWork().stream()), targets));
            case DEVELOPER ->
                feedbackChange(
                        ProfileChangeDTO.Type.FEEDBACK_RESOLVED,
                        closure.at(),
                        feedbackId,
                        ProfileChangeDTO.ResolvedBy.DEVELOPER,
                        evidence,
                        targets);
            case PRACTICE_CHANGED -> null;
        };
    }

    /** A change citing the feedback's own evidence, newest first. */
    private static ProfileChangeDTO feedbackChange(
            ProfileChangeDTO.Type type,
            Instant at,
            UUID feedbackId,
            ProfileChangeDTO.@Nullable ResolvedBy resolvedBy,
            List<Observation> evidence,
            Map<UUID, Target> targets) {
        return feedbackChange(
                type,
                at,
                feedbackId,
                resolvedBy,
                null,
                evidence.getFirst().getPractice(),
                refs(Work.newestFirst(evidence.stream().map(Work::of)), targets));
    }

    /** The practice is the one the feedback is about, read off its newest evidence. */
    private static ProfileChangeDTO feedbackChange(
            ProfileChangeDTO.Type type,
            Instant at,
            UUID feedbackId,
            ProfileChangeDTO.@Nullable ResolvedBy resolvedBy,
            @Nullable Integer cleanNeeded,
            Practice practice,
            List<ReviewedWorkRefDTO> work) {
        PracticeGroup group = practice.getGroup();
        return new ProfileChangeDTO(
                type,
                at,
                practice.getSlug(),
                practice.getName(),
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

    private static Map<String, List<Observation>> liveRunsByPractice(StandingSnapshot snapshot) {
        Map<String, List<Observation>> byPractice = new LinkedHashMap<>();
        snapshot.practices().forEach((slug, practice) -> byPractice.put(slug, practice.liveRuns()));
        return byPractice;
    }

    /** One feedback change per practice and type: the newest piece of feedback speaks for a practice that had two. */
    private static List<ProfileChangeDTO> newestFeedbackChangePerPractice(List<ProfileChangeDTO> newestFirst) {
        record PracticeAndType(@Nullable String practiceSlug, ProfileChangeDTO.Type type) {}
        Set<PracticeAndType> seen = new HashSet<>();
        return newestFirst.stream()
                .filter(change -> change.feedbackId() == null
                        || seen.add(new PracticeAndType(change.practiceSlug(), change.type())))
                .toList();
    }

    /** Which way a move between two verdicts went, or {@code null} for a move into or out of a silence. */
    private static ProfileChangeDTO.@Nullable Direction direction(
            PracticeStandingDTO.Standing from, PracticeStandingDTO.Standing to) {
        if (!PracticeStandingDTO.isVerdict(from) || !PracticeStandingDTO.isVerdict(to)) {
            return null;
        }
        return to.rank() > from.rank() ? ProfileChangeDTO.Direction.UP : ProfileChangeDTO.Direction.DOWN;
    }

    private static ProfileChangeDTO practiceChange(
            ProfileChangeDTO.Type type,
            Instant at,
            PracticeStandingDTO practice,
            @Nullable String from,
            @Nullable String to,
            ProfileChangeDTO.@Nullable Direction direction,
            List<ReviewedWorkRefDTO> evidence) {
        return new ProfileChangeDTO(
                type,
                at,
                practice.slug(),
                practice.name(),
                practice.groupSlug(),
                practice.groupName(),
                from,
                to,
                direction,
                null,
                null,
                null,
                evidence);
    }

    /** The window's work per group: its practices' work merged, one entry per piece of work, newest first. */
    private static Map<String, List<Work>> windowWorkByGroup(
            List<PracticeStandingDTO> practices, Map<String, List<Work>> windowWorkByPractice) {
        Map<String, List<Work>> byGroup = new LinkedHashMap<>();
        for (PracticeStandingDTO practice : practices) {
            if (practice.groupSlug() == null) {
                continue;
            }
            byGroup.merge(
                    practice.groupSlug(),
                    windowWorkByPractice.getOrDefault(practice.slug(), List.of()),
                    (left, right) -> Work.newestFirst(Stream.concat(left.stream(), right.stream())));
        }
        return byGroup;
    }

    private static <T> Map<String, T> bySlug(List<T> items, Function<T, String> slug) {
        return items.stream().collect(Collectors.toMap(slug, Function.identity(), (left, ignored) -> left));
    }
}
