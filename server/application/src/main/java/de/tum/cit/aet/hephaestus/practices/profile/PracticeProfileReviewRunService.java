package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository.DeliveredFeedbackCount;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationKind;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.DeveloperReviewRunRow;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationService;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.observation.VisibleRunPage;
import de.tum.cit.aet.hephaestus.practices.observation.dto.ObservationDetailDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewRunDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewRunDetailDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewRunsPageDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.SlippedPracticeDTO;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewObservationCountsDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequestStandingLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequestStandingLookup.ReviewedWorkId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunFactsLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunFactsLookup.ReviewRunFacts;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunNarrativeLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup.Target;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The developer's own review runs, across every practice group: one row per run that recorded an
 * observation about them, newest first, with what that run found about them and nothing about anybody else.
 */
@Service
@RequiredArgsConstructor
public class PracticeProfileReviewRunService {

    /** How many slipped practices a row carries: enough to name what it leads with and count the rest. */
    private static final int SLIPPED_PRACTICE_LIMIT = 3;

    private final CurrentDeveloperLookup currentDeveloperLookup;
    private final ObservationRepository observationRepository;
    private final ObservationService observationService;
    private final ObservationVisibilityPolicy visibilityPolicy;
    private final FeedbackRepository feedbackRepository;
    private final ReviewRunTargetLookup reviewRunTargetLookup;
    private final ReviewRunFactsLookup reviewRunFactsLookup;
    private final ReviewRunNarrativeLookup reviewRunNarrativeLookup;
    private final ReviewRequestStandingLookup reviewRequestStandingLookup;

    /**
     * The channels that post a run's summary, so the one that posted a comment is the one that says how to
     * open it. A list rather than one bean: a provider can be switched off, and the webhook and worker
     * runtimes boot a different slice of the context, so a row simply carries no address where its channel
     * is not here.
     */
    private final List<SummaryChannel> summaryChannels;

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
        VisibleRunPage<ProfileReviewRunDTO> page = VisibleRunPage.collect(
                pageable,
                candidates -> observationRepository.findDeveloperReviewRuns(
                        developer, workspaceId, since, null, kind, candidates),
                rows -> toRuns(workspaceId, developer, rows));
        return new ProfileReviewRunsPageDTO(
                page.content(), pageable.getPageNumber(), pageable.getPageSize(), page.hasNext());
    }

    /**
     * One run of the developer's own list. A run that recorded nothing about this developer that they may
     * see is not their run: it answers 404, the same answer a run in another workspace gets, so neither is
     * enumerable from here.
     */
    @Transactional(readOnly = true)
    public ProfileReviewRunDetailDTO getRun(WorkspaceContext workspaceContext, UUID reviewId) {
        long workspaceId = workspaceContext.id();
        long developer = currentDeveloperLookup.currentDeveloperId().orElseThrow(() -> gone(reviewId));
        List<Observation> observations =
                visibleObservations(workspaceId, developer, List.of(reviewId)).getOrDefault(reviewId, List.of());
        if (observations.isEmpty()) {
            throw gone(reviewId);
        }
        Map<UUID, Target> targets = reviewRunTargetLookup.findByJobIds(workspaceId, List.of(reviewId));
        Observation newest = observations.getFirst();
        ProfileReviewRunDTO run = toRun(
                workspaceId,
                reviewId,
                newest.getObservedAt(),
                newest.getArtifactKind(),
                newest.getArtifactId(),
                targets,
                reviewRunFactsLookup.findByJobIds(workspaceId, List.of(reviewId)),
                observations,
                delivered(workspaceId, developer, List.of(reviewId)),
                mayRequest(workspaceId, Stream.ofNullable(work(newest.getArtifactKind(), newest.getArtifactId()))));
        List<ObservationDetailDTO> details = observationService.toDetails(
                workspaceId,
                developer,
                observations,
                observations.stream().map(Observation::getId).collect(Collectors.toSet()),
                targets,
                reviewRunNarrativeLookup.findByJobIds(workspaceId, List.of(reviewId)));
        return new ProfileReviewRunDetailDTO(run, details);
    }

    private static EntityNotFoundException gone(UUID reviewId) {
        return new EntityNotFoundException("ReviewRun", reviewId.toString());
    }

    /**
     * The runs the reader may see, in the order given: a run whose every observation about them is
     * withheld is left out, and the ones left carry only the observations the gate admitted.
     */
    private List<ProfileReviewRunDTO> toRuns(long workspaceId, long developerId, List<DeveloperReviewRunRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Observation>> observations = visibleObservations(
                workspaceId,
                developerId,
                rows.stream().map(DeveloperReviewRunRow::getJobId).toList());
        List<DeveloperReviewRunRow> shown = rows.stream()
                .filter(row -> observations.containsKey(row.getJobId()))
                .toList();
        if (shown.isEmpty()) {
            return List.of();
        }
        List<UUID> jobIds = shown.stream().map(DeveloperReviewRunRow::getJobId).toList();
        Map<UUID, Target> targets = reviewRunTargetLookup.findByJobIds(workspaceId, jobIds);
        Map<UUID, ReviewRunFacts> facts = reviewRunFactsLookup.findByJobIds(workspaceId, jobIds);
        Map<UUID, Long> delivered = delivered(workspaceId, developerId, jobIds);
        // One question for the whole page: the rule loads each artifact, so asking per row would be an N+1
        // by construction.
        Set<ReviewedWorkId> mayRequest = mayRequest(
                workspaceId,
                shown.stream().map(row -> work(ArtifactKind.of(row.getArtifactKind()), row.getArtifactId())));
        return shown.stream()
                .map(row -> toRun(
                        workspaceId,
                        row.getJobId(),
                        row.getReviewedAt(),
                        ArtifactKind.of(row.getArtifactKind()),
                        row.getArtifactId(),
                        targets,
                        facts,
                        observations.getOrDefault(row.getJobId(), List.of()),
                        delivered,
                        mayRequest))
                .toList();
    }

    /**
     * What the given runs observed about the developer and the developer may see, by run, newest first
     * within a run. The same gate the detail applies, so the counts a row carries are the observations its
     * detail lists; a run with nothing left has no key.
     */
    private Map<UUID, List<Observation>> visibleObservations(
            long workspaceId, long developerId, Collection<UUID> jobIds) {
        List<Observation> found =
                observationRepository.findDeveloperReviewRunObservations(jobIds, developerId, workspaceId);
        Set<UUID> visible =
                visibilityPolicy.permitsHistory(workspaceId, found, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        return found.stream()
                .filter(row -> visible.contains(row.getId()))
                .collect(Collectors.groupingBy(Observation::getAgentJobId));
    }

    private ProfileReviewRunDTO toRun(
            long workspaceId,
            UUID jobId,
            Instant observedAt,
            ArtifactKind artifactKind,
            Long artifactId,
            Map<UUID, Target> targets,
            Map<UUID, ReviewRunFacts> facts,
            List<Observation> observations,
            Map<UUID, Long> delivered,
            Set<ReviewedWorkId> mayRequest) {
        ReviewRunFacts run = facts.get(jobId);
        ReviewedWorkId work = work(artifactKind, artifactId);
        ReviewedWorkRefDTO reviewedWork = ReviewedWorkLabels.ref(artifactKind, artifactId, targets.get(jobId));
        return new ProfileReviewRunDTO(
                jobId,
                run == null ? observedAt : run.reviewedAt(observedAt),
                reviewedWork,
                run == null ? null : run.state(),
                run == null ? null : run.triggerMode(),
                run == null ? null : run.lead(),
                run == null ? null : run.practicesEvaluated(),
                run == null ? null : run.practicesEligible(),
                run == null ? null : run.durationSeconds(),
                counts(observations),
                delivered.getOrDefault(jobId, 0L),
                feedbackUrl(reviewedWork, run),
                slipped(observations),
                work != null && mayRequest.contains(work));
    }

    /**
     * Where the feedback this run left on the work is read: the work's own page at the provider with the
     * comment the summary landed in addressed on it, which the channel that posted the comment is the only
     * one to know. Null the moment any of the three is missing, so a row falls back to saying how much
     * feedback reached the reader rather than linking somewhere that does not hold it.
     */
    private @Nullable String feedbackUrl(ReviewedWorkRefDTO work, @Nullable ReviewRunFacts run) {
        String commentId = run == null ? null : run.deliveryCommentId();
        String workUrl = work.url();
        IntegrationKind provider = work.provider();
        if (commentId == null || workUrl == null || provider == null) {
            return null;
        }
        return summaryChannels.stream()
                .filter(channel -> channel.kind() == provider)
                .findFirst()
                .map(channel -> channel.summaryCommentUrl(workUrl, commentId))
                .orElse(null);
    }

    /**
     * What slipped in this run, as the practices it recorded a problem about: the row leads with them, so it
     * takes their names rather than a count the reader would have to open the run to read. In the order the
     * observations arrived and at most three, one entry per practice however many observations it drew.
     */
    private static List<SlippedPracticeDTO> slipped(List<Observation> observations) {
        Map<String, SlippedPracticeDTO> bySlug = new LinkedHashMap<>();
        for (Observation observation : observations) {
            if (!ObservationKind.of(observation).isNegative()) {
                continue;
            }
            var practice = observation.getPractice();
            bySlug.putIfAbsent(practice.getSlug(), new SlippedPracticeDTO(practice.getSlug(), practice.getName()));
            if (bySlug.size() == SLIPPED_PRACTICE_LIMIT) {
                break;
            }
        }
        return List.copyOf(bySlug.values());
    }

    /**
     * The piece of work a run reviewed, as the request rule addresses it; null when the observation carries
     * no artifact id, which is a row nobody can ask a review of.
     */
    private static @Nullable ReviewedWorkId work(ArtifactKind artifactKind, @Nullable Long artifactId) {
        return artifactId == null ? null : new ReviewedWorkId(artifactKind, artifactId);
    }

    /** Which of these pieces of work this reader may ask for a review of, asked of the request front door. */
    private Set<ReviewedWorkId> mayRequest(long workspaceId, Stream<@Nullable ReviewedWorkId> works) {
        return reviewRequestStandingLookup.mayRequest(
                workspaceId, works.filter(Objects::nonNull).toList());
    }

    private Map<UUID, Long> delivered(long workspaceId, long developerId, Collection<UUID> jobIds) {
        return feedbackRepository.countDeliveredByRun(workspaceId, developerId, jobIds).stream()
                .collect(Collectors.toMap(DeliveredFeedbackCount::getJobId, DeliveredFeedbackCount::getDelivered));
    }

    /** The observations by assessment, tallied over the rows the reader may see. */
    private static ReviewObservationCountsDTO counts(List<Observation> observations) {
        List<ObservationKind> kinds =
                observations.stream().map(ObservationKind::of).toList();
        return new ReviewObservationCountsDTO(
                kinds.stream().filter(ObservationKind::isPositive).count(),
                kinds.stream().filter(ObservationKind::isNegative).count(),
                kinds.stream().filter(ObservationKind.NOT_APPLICABLE::equals).count(),
                kinds.stream().filter(ObservationKind.UNDETERMINED::equals).count());
    }
}
