package de.tum.cit.aet.hephaestus.practices.groupdetail;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.groupdetail.dto.PracticeGroupReviewRunDTO;
import de.tum.cit.aet.hephaestus.practices.groupdetail.dto.PracticeGroupReviewRunsPageDTO;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.ReviewRunRow;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationService;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.observation.VisibleRunPage;
import de.tum.cit.aet.hephaestus.practices.observation.VisibleRunPage.VisibleRun;
import de.tum.cit.aet.hephaestus.practices.observation.dto.ObservationDetailDTO;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunNarrativeLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunNarrativeLookup.ReviewRunNarrative;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeGroupReviewRunService {

    private final PracticeGroupService practiceGroupService;
    private final ObservationRepository observationRepository;
    private final ObservationService observationService;
    private final ReviewRunLookup reviewRunLookup;
    private final ReviewRunNarrativeLookup reviewRunNarrativeLookup;
    private final CurrentDeveloperLookup currentDeveloperLookup;
    private final ObservationVisibilityPolicy visibilityPolicy;

    @Transactional(readOnly = true)
    public PracticeGroupReviewRunsPageDTO list(
            WorkspaceContext workspaceContext, String groupSlug, RunFilters filter, Pageable pageable) {
        var currentDeveloperId = currentDeveloperLookup.currentDeveloperId();
        if (currentDeveloperId.isEmpty()) {
            practiceGroupService.getGroup(workspaceContext, groupSlug);
            return new PracticeGroupReviewRunsPageDTO(
                    List.of(), pageable.getPageNumber(), pageable.getPageSize(), false);
        }
        practiceGroupService.getGroup(workspaceContext, groupSlug);
        return loadRuns(workspaceContext, currentDeveloperId.get(), groupSlug, null, filter, pageable);
    }

    /**
     * The reader's own complete review runs of one piece of work, every practice group together: where a note
     * posted on the work sends its developer to answer it.
     */
    @Transactional(readOnly = true)
    public PracticeGroupReviewRunsPageDTO listForWork(
            WorkspaceContext workspaceContext, ArtifactKind artifactKind, long artifactId, Pageable pageable) {
        var currentDeveloperId = currentDeveloperLookup.currentDeveloperId();
        if (currentDeveloperId.isEmpty()) {
            return new PracticeGroupReviewRunsPageDTO(
                    List.of(), pageable.getPageNumber(), pageable.getPageSize(), false);
        }
        return loadRuns(
                workspaceContext,
                currentDeveloperId.get(),
                null,
                new Work(artifactKind, artifactId),
                new RunFilters(null, null, null),
                pageable);
    }

    private record Work(ArtifactKind kind, long id) {}

    public record RunFilters(
            @Nullable String practiceSlug,
            @Nullable List<ArtifactKind> artifactKinds,
            @Nullable List<Severity> severities) {}

    private PracticeGroupReviewRunsPageDTO loadRuns(
            WorkspaceContext workspaceContext,
            long developerId,
            @Nullable String groupSlug,
            @Nullable Work work,
            RunFilters filter,
            Pageable pageable) {
        var practiceSlug = filter.practiceSlug();
        var artifactKinds = filter.artifactKinds();
        var severities = filter.severities();

        String artifactFilter = artifactKinds == null || artifactKinds.isEmpty()
                ? null
                : artifactKinds.stream().map(ArtifactKind::value).collect(Collectors.joining(","));
        String severityFilter = severities == null || severities.isEmpty()
                ? null
                : severities.stream().map(Enum::name).collect(Collectors.joining(","));

        long workspaceId = workspaceContext.id();
        VisibleRunPage page = VisibleRunPage.collect(
                pageable,
                candidates -> observationRepository.findPracticeGroupReviewRuns(
                        developerId,
                        workspaceId,
                        groupSlug,
                        work == null ? null : work.kind().value(),
                        work == null ? null : work.id(),
                        practiceSlug,
                        artifactFilter,
                        severityFilter,
                        candidates),
                runs -> visibleObservations(workspaceId, developerId, runs, groupSlug));
        return new PracticeGroupReviewRunsPageDTO(
                toRuns(workspaceId, developerId, page.content()),
                pageable.getPageNumber(),
                pageable.getPageSize(),
                page.hasNext());
    }

    private Map<UUID, List<Observation>> visibleObservations(
            long workspaceId, long developerId, List<ReviewRunRow> runs, @Nullable String groupSlug) {
        List<Observation> found = observationRepository.findPracticeGroupReviewRunObservations(
                runs.stream().map(ReviewRunRow::getJobId).toList(), developerId, workspaceId, groupSlug);
        Set<UUID> visible =
                visibilityPolicy.permitsHistory(workspaceId, found, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        return found.stream()
                .filter(row -> visible.contains(row.getId()))
                .collect(Collectors.groupingBy(Observation::getAgentJobId));
    }

    private List<PracticeGroupReviewRunDTO> toRuns(long workspaceId, long developerId, List<VisibleRun> runs) {
        if (runs.isEmpty()) {
            return List.of();
        }
        List<UUID> jobIds = runs.stream().map(VisibleRun::reviewId).toList();
        List<Observation> observations =
                runs.stream().flatMap(run -> run.observations().stream()).toList();
        Map<UUID, ReviewRunLookup.Target> targets = reviewRunLookup.findTargets(workspaceId, jobIds);
        Map<UUID, ReviewRunNarrative> narratives = reviewRunNarrativeLookup.findByJobIds(workspaceId, jobIds);
        Map<UUID, UUID> jobByObservation =
                observations.stream().collect(Collectors.toMap(Observation::getId, Observation::getAgentJobId));
        // The visibility gate admitted every observation left, so each carries its evidence.
        Map<UUID, List<ObservationDetailDTO>> detailsByJob = observationService
                .toDetails(workspaceId, developerId, observations, jobByObservation.keySet(), targets, narratives)
                .stream()
                .collect(Collectors.groupingBy(detail -> Objects.requireNonNull(jobByObservation.get(detail.id()))));
        return runs.stream()
                .map(visible -> {
                    UUID jobId = visible.reviewId();
                    Observation newest = visible.observations().getFirst();
                    return new PracticeGroupReviewRunDTO(
                            jobId,
                            visible.reviewedAt(),
                            ReviewedWorkLabels.ref(
                                    newest.getArtifactKind(), newest.getArtifactId(), targets.get(jobId)),
                            detailsByJob.getOrDefault(jobId, List.of()));
                })
                .toList();
    }
}
