package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository.DeliveredFeedbackCount;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationInvalidationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.DeveloperReviewRunRow;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationService;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.observation.VisibleRunPage;
import de.tum.cit.aet.hephaestus.practices.observation.VisibleRunPage.VisibleRun;
import de.tum.cit.aet.hephaestus.practices.observation.dto.ObservationDetailDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewRunDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewRunDetailDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewRunsPageDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ReviewPracticeOutcomesDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.SlippedPracticeDTO;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequestStandingLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequestStandingLookup.ReviewedWorkId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup.ReviewRunFacts;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunNarrativeLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The developer's own review runs across every practice group: one row per run that recorded an observation
 * about them, newest first, narrowed to what it found about them.
 */
@Service
@RequiredArgsConstructor
public class PracticeProfileReviewRunService {

    private final CurrentDeveloperLookup currentDeveloperLookup;
    private final ObservationRepository observationRepository;
    private final ObservationInvalidationRepository invalidationRepository;
    private final ObservationService observationService;
    private final ObservationVisibilityPolicy visibilityPolicy;
    private final FeedbackRepository feedbackRepository;
    private final ReviewRunLookup reviewRunLookup;
    private final ReviewRunNarrativeLookup reviewRunNarrativeLookup;
    private final ReviewRequestStandingLookup reviewRequestStandingLookup;

    @Transactional(readOnly = true)
    public ProfileReviewRunsPageDTO list(
            WorkspaceContext workspaceContext,
            @Nullable ArtifactKind artifactKind,
            @Nullable Instant since,
            Pageable pageable) {
        var developerId = currentDeveloperLookup.currentDeveloperId();
        if (developerId.isEmpty()) {
            return new ProfileReviewRunsPageDTO(List.of(), pageable.getPageNumber(), pageable.getPageSize(), false);
        }
        long workspaceId = workspaceContext.id();
        long developer = developerId.get();
        String kind = artifactKind == null ? null : artifactKind.value();
        VisibleRunPage page = VisibleRunPage.collect(
                pageable,
                candidates -> observationRepository.findDeveloperReviewRuns(
                        developer, workspaceId, since, null, kind, candidates),
                rows -> visibleByRun(
                        workspaceId,
                        observationRepository.findDeveloperReviewRunObservations(
                                rows.stream()
                                        .map(DeveloperReviewRunRow::getJobId)
                                        .toList(),
                                developer,
                                workspaceId)));
        List<ProfileReviewRunDTO> runs = toRuns(
                workspaceId,
                developer,
                page.content(),
                reviewRunLookup.findFacts(
                        workspaceId,
                        page.content().stream().map(VisibleRun::reviewId).toList()));
        return new ProfileReviewRunsPageDTO(runs, pageable.getPageNumber(), pageable.getPageSize(), page.hasNext());
    }

    /**
     * One run of the developer's own list. A run with nothing about this developer that they may see answers
     * 404, the same answer a run in another workspace gets.
     */
    @Transactional(readOnly = true)
    public ProfileReviewRunDetailDTO getRun(WorkspaceContext workspaceContext, UUID reviewId) {
        long workspaceId = workspaceContext.id();
        long developer = currentDeveloperLookup.currentDeveloperId().orElseThrow(() -> gone(reviewId));
        VisibleRun visible = visibleRun(workspaceId, developer, reviewId).orElseThrow(() -> gone(reviewId));
        List<Observation> observations = visible.observations();
        Map<UUID, ReviewRunFacts> facts = reviewRunLookup.findFacts(workspaceId, List.of(reviewId));
        ProfileReviewRunDTO run =
                toRuns(workspaceId, developer, List.of(visible), facts).getFirst();
        List<ObservationDetailDTO> details = observationService.toDetails(
                workspaceId,
                developer,
                observations,
                observations.stream().map(Observation::getId).collect(Collectors.toSet()),
                facts.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey, entry -> entry.getValue().target())),
                reviewRunNarrativeLookup.findByJobIds(workspaceId, List.of(reviewId)));
        return new ProfileReviewRunDetailDTO(run, details);
    }

    /**
     * The calling developer, when this review is one {@link #getRun} would open for them and it observed them on
     * this work; empty otherwise.
     */
    @Transactional(readOnly = true)
    public Optional<Long> ownRunDeveloperOn(
            long workspaceId, UUID reviewId, ArtifactKind artifactKind, long artifactId) {
        return currentDeveloperLookup
                .currentDeveloperId()
                .filter(developer -> visibleRun(workspaceId, developer, reviewId)
                        .filter(run -> run.observations().stream()
                                .anyMatch(observation -> artifactKind.equals(observation.getArtifactKind())
                                        && observation.getArtifactId() == artifactId))
                        .isPresent());
    }

    /** The run as the developer may see it, or empty when nothing it recorded about them is theirs to read. */
    private Optional<VisibleRun> visibleRun(long workspaceId, long developer, UUID reviewId) {
        List<Observation> found =
                observationRepository.findDeveloperReviewRunObservations(List.of(reviewId), developer, workspaceId);
        List<Observation> observations = visibleByRun(workspaceId, found).getOrDefault(reviewId, List.of());
        if (observations.isEmpty()) {
            return Optional.empty();
        }
        // Dated by the newest of the rows the list's date aggregates, so the run carries one date on both.
        return Optional.of(new VisibleRun(reviewId, found.getFirst().getObservedAt(), observations));
    }

    private static EntityNotFoundException gone(UUID reviewId) {
        return new EntityNotFoundException("ReviewRun", reviewId.toString());
    }

    /** The observations the developer may see, by run, in the order found. A run with nothing left has no key. */
    private Map<UUID, List<Observation>> visibleByRun(long workspaceId, List<Observation> found) {
        Set<UUID> visible =
                visibilityPolicy.permitsHistory(workspaceId, found, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        return found.stream()
                .filter(row -> visible.contains(row.getId()))
                .collect(Collectors.groupingBy(Observation::getAgentJobId));
    }

    /** The rows for these runs, in their order; each run's observations are non-empty and newest first. */
    private List<ProfileReviewRunDTO> toRuns(
            long workspaceId, long developerId, List<VisibleRun> runs, Map<UUID, ReviewRunFacts> facts) {
        if (runs.isEmpty()) {
            return List.of();
        }
        List<UUID> jobIds = runs.stream().map(VisibleRun::reviewId).toList();
        Map<UUID, Long> delivered = feedbackRepository.countDeliveredByRun(workspaceId, developerId, jobIds).stream()
                .collect(Collectors.toMap(DeliveredFeedbackCount::getJobId, DeliveredFeedbackCount::getDelivered));
        Set<UUID> invalidated = invalidationRepository.findActiveObservationIds(
                workspaceId,
                runs.stream()
                        .flatMap(run -> run.observations().stream())
                        .map(Observation::getId)
                        .toList());
        Set<ReviewedWorkId> mayRequest = reviewRequestStandingLookup.mayRequest(
                workspaceId,
                runs.stream().map(run -> work(run.observations().getFirst())).toList());
        return runs.stream()
                .map(visible -> {
                    UUID jobId = visible.reviewId();
                    Observation newest = visible.observations().getFirst();
                    Map<Practice, PracticeOutcome> outcomes = visible.observations().stream()
                            .filter(observation -> !invalidated.contains(observation.getId()))
                            .collect(Collectors.groupingBy(
                                    Observation::getPractice,
                                    Collectors.mapping(
                                            Observation::getOutcome,
                                            Collectors.collectingAndThen(Collectors.toList(), PracticeOutcome::of))));
                    ReviewRunFacts run = facts.get(jobId);
                    return new ProfileReviewRunDTO(
                            jobId,
                            visible.reviewedAt(),
                            ReviewedWorkLabels.ref(
                                    newest.getArtifactKind(),
                                    newest.getArtifactId(),
                                    run == null ? null : run.target()),
                            run == null ? null : run.status(),
                            run == null ? null : run.triggerMode(),
                            run == null ? null : run.practicesEvaluated(),
                            practices(outcomes.values()),
                            delivered.getOrDefault(jobId, 0L),
                            run == null ? null : run.feedbackUrl(),
                            slipped(outcomes),
                            mayRequest.contains(work(newest)));
                })
                .toList();
    }

    /** What a run decided about one practice for this developer, from every standing observation of it. */
    private enum PracticeOutcome {
        TO_IMPROVE,
        HELD,
        NOT_APPLICABLE,
        UNDECIDED;

        /** Any problem outweighs any strength. */
        static PracticeOutcome of(List<Outcome> kinds) {
            if (kinds.stream().anyMatch(Outcome::isNotMet)) {
                return TO_IMPROVE;
            }
            if (kinds.stream().anyMatch(Outcome::isMet)) {
                return HELD;
            }
            return kinds.stream().allMatch(Outcome.NOT_APPLICABLE::equals) ? NOT_APPLICABLE : UNDECIDED;
        }
    }

    private static ReviewPracticeOutcomesDTO practices(Collection<PracticeOutcome> outcomes) {
        return new ReviewPracticeOutcomesDTO(
                count(outcomes, PracticeOutcome.TO_IMPROVE),
                count(outcomes, PracticeOutcome.HELD),
                count(outcomes, PracticeOutcome.NOT_APPLICABLE),
                count(outcomes, PracticeOutcome.UNDECIDED));
    }

    private static int count(Collection<PracticeOutcome> outcomes, PracticeOutcome outcome) {
        return (int) outcomes.stream().filter(outcome::equals).count();
    }

    /** The practices counted as to improve, by name. */
    private static List<SlippedPracticeDTO> slipped(Map<Practice, PracticeOutcome> outcomes) {
        return outcomes.entrySet().stream()
                .filter(entry -> entry.getValue() == PracticeOutcome.TO_IMPROVE)
                .map(entry -> new SlippedPracticeDTO(
                        entry.getKey().getSlug(), entry.getKey().getName()))
                .sorted(Comparator.comparing(SlippedPracticeDTO::practiceName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(SlippedPracticeDTO::practiceSlug))
                .toList();
    }

    private static ReviewedWorkId work(Observation observation) {
        return new ReviewedWorkId(observation.getArtifactKind(), observation.getArtifactId());
    }
}
