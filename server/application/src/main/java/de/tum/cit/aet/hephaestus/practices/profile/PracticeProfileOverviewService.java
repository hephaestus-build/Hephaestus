package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.practices.curated.BundledPracticeCatalogLoader;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.DeveloperReviewRunRow;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService.StandingSnapshot;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService.StandingSnapshot.PracticeStanding;
import de.tum.cit.aet.hephaestus.practices.observation.trend.PracticeTrend;
import de.tum.cit.aet.hephaestus.practices.observation.trend.WorkResolution.Work;
import de.tum.cit.aet.hephaestus.practices.profile.dto.HeldPracticeDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.OverviewWindowDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.PracticeProfileOverviewDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ReviewRunRefDTO;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup.ReviewRunFacts;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup.Target;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composes the practice profile's overview from the read models that already exist: the developer's own review
 * runs, what changed inside the window ({@link ProfileChangeDetector}) and the strengths that held.
 */
@Service
@RequiredArgsConstructor
public class PracticeProfileOverviewService {

    private final CurrentDeveloperLookup currentDeveloperLookup;
    private final ProfileChangeDetector changeDetector;
    private final PracticeStandingService practiceStandingService;
    private final ObservationRepository observationRepository;
    private final ReviewRunLookup reviewRunLookup;
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
        List<DeveloperReviewRunRow> newestRuns = observationRepository
                .findDeveloperReviewRuns(developerId, workspaceId, null, now, null, PageRequest.of(0, 2))
                .getContent();
        DeveloperReviewRunRow latest = newestRuns.isEmpty() ? null : newestRuns.getFirst();
        Instant previousRunAt = newestRuns.size() < 2 ? null : newestRuns.get(1).getReviewedAt();
        OverviewWindow window = OverviewWindow.sincePreviousRun(previousRunAt, now);
        List<DeveloperReviewRunRow> windowRuns = newestRuns.stream()
                .filter(run -> window.contains(run.getReviewedAt()))
                .toList();

        ProfileChangeDetector.WindowChanges changes = changeDetector.detect(
                workspaceId, developerId, window, windowRuns, latest == null ? List.of() : List.of(latest.getJobId()));
        Map<UUID, Target> targets = changes.targets();
        return new PracticeProfileOverviewDTO(
                OverviewWindowDTO.from(window),
                latest == null ? null : runRef(workspaceId, latest, targets),
                holdingUp(changes.after(), targets),
                changes.changes(),
                reviewedWork(windowRuns, targets));
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
            if (kind == null || since == null || !practice.isHolding(practiceStandingService.standingWindow())) {
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

    private ReviewRunRefDTO runRef(long workspaceId, DeveloperReviewRunRow run, Map<UUID, Target> targets) {
        ReviewRunFacts facts =
                reviewRunLookup.findFacts(workspaceId, List.of(run.getJobId())).get(run.getJobId());
        return new ReviewRunRefDTO(
                run.getJobId(),
                run.getReviewedAt(),
                ReviewedWorkLabels.ref(
                        ArtifactKind.of(run.getArtifactKind()), run.getArtifactId(), targets.get(run.getJobId())),
                facts == null ? null : facts.status());
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
