package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.curated.BundledPracticeCatalogLoader;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
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
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService.StandingSnapshot.PracticeStanding;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository.AddressedFeedbackProjection;
import de.tum.cit.aet.hephaestus.practices.observation.trend.PracticeTrend;
import de.tum.cit.aet.hephaestus.practices.observation.trend.WorkResolution;
import de.tum.cit.aet.hephaestus.practices.observation.trend.WorkResolution.Work;
import de.tum.cit.aet.hephaestus.practices.profile.dto.HeldPracticeDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.OverviewWindowDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.PracticeProfileOverviewDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileChangeDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ReviewRunRefDTO;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup.Target;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composes the practice profile's overview from the read models that already exist: the standings as of the
 * two edges of the window, the developer's own review runs, their readable in-app feedback, what their work
 * has said about it since and their responses to it.
 */
@Service
@RequiredArgsConstructor
public class PracticeProfileOverviewService {

    private final CurrentDeveloperLookup currentDeveloperLookup;
    private final PracticeStandingService practiceStandingService;
    private final PracticeGroupStandingService practiceGroupStandingService;
    private final PracticeGroupService practiceGroupService;
    private final ObservationRepository observationRepository;
    private final FeedbackRepository feedbackRepository;
    private final InAppFeedbackEvidence feedbackEvidence;
    private final ReactionRepository reactionRepository;
    private final ReviewRunTargetLookup reviewRunTargetLookup;
    private final BundledPracticeCatalogLoader bundledCatalog;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PracticeProfileOverviewDTO getOverview(WorkspaceContext workspaceContext) {
        Instant now = clock.instant();
        Long workspaceId = workspaceContext.id();
        Optional<Long> currentDeveloperId = currentDeveloperLookup.currentDeveloperId();
        if (currentDeveloperId.isEmpty()) {
            OverviewWindow window = OverviewWindow.sincePreviousRun(null, now);
            return new PracticeProfileOverviewDTO(
                    OverviewWindowDTO.from(window), null, List.of(), List.of(), List.of());
        }
        long developerId = currentDeveloperId.get();

        // The two newest runs; the previous one opens the window, so the window holds no run older than these.
        List<DeveloperReviewRunRow> newestRuns = observationRepository.findDeveloperReviewRuns(
                developerId, workspaceId, null, now, PageRequest.of(0, 2));
        DeveloperReviewRunRow latest = newestRuns.isEmpty() ? null : newestRuns.getFirst();
        Instant previousRunAt = newestRuns.size() < 2 ? null : newestRuns.get(1).getReviewedAt();
        OverviewWindow window = OverviewWindow.sincePreviousRun(previousRunAt, now);
        List<DeveloperReviewRunRow> windowRuns = newestRuns.stream()
                .filter(run -> window.contains(run.getReviewedAt()))
                .toList();

        List<StandingSnapshot> edges = practiceStandingService.getStandingSnapshots(
                workspaceId, developerId, List.of(window.since(), window.until()));
        StandingSnapshot before = edges.get(0);
        StandingSnapshot after = edges.get(1);
        List<PracticeGroup> groups = practiceGroupService.listGroups(workspaceContext, true);
        List<PracticeGroupStandingDTO> groupsBefore = practiceGroupStandingService.summarize(groups, before);
        List<PracticeGroupStandingDTO> groupsAfter = practiceGroupStandingService.summarize(groups, after);

        Map<String, List<Work>> windowWorkByPractice = windowWorkByPractice(after, window);
        Map<String, Instant> firstObservedAt =
                observationRepository.findFirstObservedAtByPractice(developerId, workspaceId).stream()
                        .collect(Collectors.toMap(
                                FirstObservedRow::getPracticeSlug, FirstObservedRow::getFirstObservedAt));
        FeedbackFacts feedback = readFeedback(workspaceId, developerId, now, window, windowRuns, before, after);

        Map<UUID, Target> targets = reviewRunTargetLookup.findByJobIds(
                workspaceId,
                Stream.of(
                                windowRuns.stream().map(DeveloperReviewRunRow::getJobId),
                                Stream.ofNullable(latest).map(DeveloperReviewRunRow::getJobId),
                                windowWorkByPractice.values().stream()
                                        .flatMap(List::stream)
                                        .map(Work::jobId),
                                after.practices().values().stream()
                                        .flatMap(practice -> practice.trend().cleanWork().jobIds().stream()),
                                feedback.citedJobIds())
                        .flatMap(ids -> ids)
                        .collect(Collectors.toSet()));

        List<ProfileChangeDTO> changes = new ArrayList<>(ProfileChangeDetector.detect(
                window,
                before.dtos(),
                after.dtos(),
                groupsBefore,
                groupsAfter,
                windowWorkByPractice,
                firstObservedAt,
                targets));
        changes.addAll(feedbackChanges(feedback, window, targets));
        changes.sort(Comparator.comparing(ProfileChangeDTO::at).reversed());
        return new PracticeProfileOverviewDTO(
                OverviewWindowDTO.from(window),
                latest == null ? null : runRef(latest, targets),
                holdingUp(after, targets),
                newestFeedbackChangePerPractice(changes),
                reviewedWork(windowRuns, targets));
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
     * Feedback prepared inside the standing's look-back, and nothing older
     * ({@code docs/contributor/practice-review-glossary.mdx} § Practice profile overview). The work's resolution
     * is read for every piece, not only those with work in the window: a resolution by work before the window is
     * what makes a later answer inside it no change at all, and the window filter on the changes decides what is
     * reported.
     */
    private FeedbackFacts readFeedback(
            Long workspaceId,
            long developerId,
            Instant now,
            OverviewWindow window,
            List<DeveloperReviewRunRow> windowRuns,
            StandingSnapshot before,
            StandingSnapshot after) {
        Instant lookback = now.minus(OverviewWindow.LOOKBACK);
        List<Feedback> recent = feedbackRepository.findReadableInAppPreparedSince(workspaceId, developerId, lookback);
        Set<UUID> recentIds = recent.stream().map(Feedback::getId).collect(Collectors.toSet());
        // Responses from the feedback's start, not from the window's edge: feedback the developer marked
        // addressed before the window opened and whose work then resolved it inside the window resolved before
        // the window, once.
        List<AddressedFeedbackProjection> addressed = reactionRepository
                .findInAppResolvedByDeveloperBetween(
                        developerId, workspaceId, lookback, window.until(), FeedbackResolution.resolvingNames())
                .stream()
                .filter(response -> recentIds.contains(response.getFeedbackId()))
                .toList();
        Map<UUID, List<Observation>> evidenceByFeedback = feedbackEvidence.visibleEvidence(workspaceId, recentIds);
        Map<UUID, Instant> practiceChangedAt = feedbackEvidence.practiceChangedAt(evidenceByFeedback);
        return new FeedbackFacts(
                recent,
                windowRuns.stream()
                        .collect(Collectors.toMap(
                                DeveloperReviewRunRow::getJobId, DeveloperReviewRunRow::getReviewedAt)),
                evidenceByFeedback,
                InAppFeedbackEvidence.workResolutionsFrom(
                        recent, evidenceByFeedback, practiceChangedAt, evidenceByPractice(after)),
                InAppFeedbackEvidence.workResolutionsFrom(
                        recent, evidenceByFeedback, practiceChangedAt, evidenceByPractice(before)),
                addressed.stream()
                        .collect(Collectors.toMap(
                                AddressedFeedbackProjection::getFeedbackId,
                                AddressedFeedbackProjection::getRespondedAt)),
                practiceChangedAt);
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
                ProfileChangeDetector.refs(problems, targets));
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
                        ProfileChangeDetector.refs(Work.newestFirst(resolution.cleanWork().stream()), targets));
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
                ProfileChangeDetector.refs(Work.newestFirst(evidence.stream().map(Work::of)), targets));
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

    private static Map<String, List<Observation>> evidenceByPractice(StandingSnapshot snapshot) {
        Map<String, List<Observation>> byPractice = new LinkedHashMap<>();
        snapshot.practices().forEach((slug, practice) -> byPractice.put(slug, practice.evidence()));
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

    /** Strengths whose newest pieces of work all came back clean, longest run first. */
    private List<HeldPracticeDTO> holdingUp(StandingSnapshot after, Map<UUID, Target> targets) {
        List<HeldPracticeDTO> held = new ArrayList<>();
        for (PracticeStanding practice : after.practices().values()) {
            if (practice.evidence().isEmpty()) {
                continue;
            }
            PracticeTrend.CleanWork cleanWork = practice.trend().cleanWork();
            ArtifactKind kind = cleanWork.kind();
            Instant since = cleanWork.since();
            if (kind == null || since == null || !practice.isHolding()) {
                continue;
            }
            held.add(new HeldPracticeDTO(
                    practice.dto().slug(),
                    practice.dto().name(),
                    practice.dto().groupSlug(),
                    holdsAs(practice.evidence().getFirst().getPractice()),
                    cleanWork.count(),
                    kind.value(),
                    workProvider(cleanWork, targets),
                    since));
        }
        held.sort(Comparator.comparingInt(HeldPracticeDTO::cleanWork)
                .reversed()
                .thenComparing(HeldPracticeDTO::practiceName));
        return held;
    }

    /**
     * The provider most of the clean work lives at, like {@code kind} is the kind most of it is — the two
     * are the noun's parts. A piece whose run is gone names no provider and is left out of the count.
     */
    private static @Nullable IntegrationKind workProvider(
            PracticeTrend.CleanWork cleanWork, Map<UUID, Target> targets) {
        return PracticeTrend.CleanWork.mostOf(cleanWork.jobIds().stream()
                .map(targets::get)
                .filter(Objects::nonNull)
                .map(Target::provider)
                .filter(Objects::nonNull));
    }

    /**
     * The catalog's phrase for a workspace practice copied from it, found by the bundled slug it came from
     * whatever it was renamed to since. A practice created in the workspace has none.
     */
    private @Nullable String holdsAs(Practice practice) {
        String sourceSlug = practice.getSourceCuratedSlug();
        return sourceSlug == null ? null : bundledCatalog.holdsAs(sourceSlug).orElse(null);
    }

    private static ReviewRunRefDTO runRef(DeveloperReviewRunRow run, Map<UUID, Target> targets) {
        return new ReviewRunRefDTO(
                run.getJobId(),
                run.getReviewedAt(),
                ReviewedWorkLabels.ref(
                        ArtifactKind.of(run.getArtifactKind()), run.getArtifactId(), targets.get(run.getJobId())));
    }

    /** The window's runs as pieces of work: a piece reviewed twice in the window is listed once, at its newest. */
    private static List<ReviewedWorkRefDTO> reviewedWork(
            List<DeveloperReviewRunRow> windowRuns, Map<UUID, Target> targets) {
        return ProfileChangeDetector.refs(
                Work.newestFirst(windowRuns.stream()
                        .map(run -> new Work(
                                ArtifactKind.of(run.getArtifactKind()),
                                run.getArtifactId(),
                                run.getJobId(),
                                run.getReviewedAt()))),
                targets);
    }
}
