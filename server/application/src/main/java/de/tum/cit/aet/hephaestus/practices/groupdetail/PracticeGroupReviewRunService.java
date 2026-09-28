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
import de.tum.cit.aet.hephaestus.practices.observation.dto.ObservationDetailDTO;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunNarrativeLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunNarrativeLookup.ReviewRunNarrative;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.ArrayList;
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
    private final ReviewRunTargetLookup reviewRunTargetLookup;
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

        VisibleRunPage<PracticeGroupReviewRunDTO> page = VisibleRunPage.collect(
                pageable,
                candidates -> observationRepository.findPracticeGroupReviewRuns(
                        developerId,
                        workspaceContext.id(),
                        groupSlug,
                        work == null ? null : work.kind().value(),
                        work == null ? null : work.id(),
                        practiceSlug,
                        artifactFilter,
                        severityFilter,
                        candidates),
                runs -> toVisibleRuns(workspaceContext.id(), developerId, runs, groupSlug));
        return new PracticeGroupReviewRunsPageDTO(
                page.content(), pageable.getPageNumber(), pageable.getPageSize(), page.hasNext());
    }

    private List<PracticeGroupReviewRunDTO> toVisibleRuns(
            long workspaceId, long developerId, List<ReviewRunRow> runs, @Nullable String groupSlug) {
        if (runs.isEmpty()) {
            return List.of();
        }

        List<UUID> jobIds = runs.stream().map(ReviewRunRow::getJobId).toList();
        List<Observation> found = observationRepository.findPracticeGroupReviewRunObservations(
                jobIds, developerId, workspaceId, groupSlug);
        Set<UUID> visible =
                visibilityPolicy.permitsHistory(workspaceId, found, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        List<Observation> observations =
                found.stream().filter(row -> visible.contains(row.getId())).toList();
        Map<UUID, UUID> jobByObservation =
                observations.stream().collect(Collectors.toMap(Observation::getId, Observation::getAgentJobId));
        Map<UUID, ReviewRunTargetLookup.Target> targets = reviewRunTargetLookup.findByJobIds(workspaceId, jobIds);
        Map<UUID, ReviewRunNarrative> narratives = reviewRunNarrativeLookup.findByJobIds(workspaceId, jobIds);
        // The visibility gate admitted every observation left, so each carries its evidence — the same
        // authorization answer the detail endpoint reads, taken once for the page.
        Map<UUID, List<ObservationDetailDTO>> detailsByJob =
                observationService
                        .toDetails(workspaceId, developerId, observations, visible, targets, narratives)
                        .stream()
                        .collect(Collectors.groupingBy(
                                detail -> Objects.requireNonNull(jobByObservation.get(detail.id()))));

        List<PracticeGroupReviewRunDTO> reviewRuns = new ArrayList<>();
        for (ReviewRunRow run : runs) {
            List<ObservationDetailDTO> details = detailsByJob.getOrDefault(run.getJobId(), List.of());
            if (!details.isEmpty()) {
                ObservationDetailDTO first = details.getFirst();
                ReviewedWorkRefDTO reviewedWork =
                        ReviewedWorkLabels.ref(first.artifactKind(), first.artifactId(), targets.get(run.getJobId()));
                reviewRuns.add(
                        new PracticeGroupReviewRunDTO(run.getJobId(), run.getReviewedAt(), reviewedWork, details));
            }
        }
        return reviewRuns;
    }
}
