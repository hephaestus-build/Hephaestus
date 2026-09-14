package de.tum.cit.aet.hephaestus.practices.groupdetail;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.DeliveredFeedbackBinding;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackUsefulness;
import de.tum.cit.aet.hephaestus.practices.groupdetail.dto.PracticeGroupReviewObservationDTO;
import de.tum.cit.aet.hephaestus.practices.groupdetail.dto.PracticeGroupReviewRunDTO;
import de.tum.cit.aet.hephaestus.practices.groupdetail.dto.PracticeGroupReviewRunsPageDTO;
import de.tum.cit.aet.hephaestus.practices.groupdetail.dto.PracticeGroupReviewedWorkDTO;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.ReviewRunRow;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeGroupReviewRunService {

    private final PracticeGroupService practiceGroupService;
    private final ObservationRepository observationRepository;
    private final FeedbackObservationRepository feedbackObservationRepository;
    private final ReviewRunTargetLookup reviewRunTargetLookup;
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
        return loadRuns(workspaceContext, currentDeveloperId.get(), groupSlug, filter, pageable);
    }

    public record RunFilters(
            @Nullable String practiceSlug,
            @Nullable List<ArtifactKind> artifactKinds,
            @Nullable List<Severity> severities) {}

    @Transactional(readOnly = true)
    public PracticeGroupReviewRunsPageDTO list(
            WorkspaceContext workspaceContext,
            long developerId,
            String groupSlug,
            RunFilters filter,
            Pageable pageable) {
        return loadRuns(workspaceContext, developerId, groupSlug, filter, pageable);
    }

    private PracticeGroupReviewRunsPageDTO loadRuns(
            WorkspaceContext workspaceContext,
            long developerId,
            String groupSlug,
            RunFilters filter,
            Pageable pageable) {
        practiceGroupService.getGroup(workspaceContext, groupSlug);
        var practiceSlug = filter.practiceSlug();
        var artifactKinds = filter.artifactKinds();
        var severities = filter.severities();

        String artifactFilter = artifactKinds == null || artifactKinds.isEmpty()
                ? null
                : artifactKinds.stream().map(ArtifactKind::value).collect(Collectors.joining(","));
        String severityFilter = severities == null || severities.isEmpty()
                ? null
                : severities.stream().map(Enum::name).collect(Collectors.joining(","));

        int first = Math.multiplyExact(pageable.getPageNumber(), pageable.getPageSize());
        int required = Math.addExact(first, pageable.getPageSize() + 1);
        List<PracticeGroupReviewRunDTO> visibleRuns = new ArrayList<>(required);
        int candidatePage = 0;
        boolean moreCandidates;
        do {
            Slice<ReviewRunRow> runs = observationRepository.findPracticeGroupReviewRuns(
                    developerId,
                    workspaceContext.id(),
                    groupSlug,
                    practiceSlug,
                    artifactFilter,
                    severityFilter,
                    PageRequest.of(candidatePage++, Math.max(pageable.getPageSize(), 50)));
            visibleRuns.addAll(toVisibleRuns(workspaceContext.id(), developerId, runs.getContent(), groupSlug));
            moreCandidates = runs.hasNext();
        } while (visibleRuns.size() < required && moreCandidates);

        int end = Math.min(first + pageable.getPageSize(), visibleRuns.size());
        List<PracticeGroupReviewRunDTO> content =
                first >= visibleRuns.size() ? List.of() : List.copyOf(visibleRuns.subList(first, end));
        return new PracticeGroupReviewRunsPageDTO(
                content, pageable.getPageNumber(), pageable.getPageSize(), visibleRuns.size() > end);
    }

    private List<PracticeGroupReviewRunDTO> toVisibleRuns(
            long workspaceId, long developerId, List<ReviewRunRow> runs, String groupSlug) {
        if (runs.isEmpty()) {
            return List.of();
        }

        List<UUID> jobIds = runs.stream().map(ReviewRunRow::getJobId).toList();
        List<Observation> found = observationRepository.findPracticeGroupReviewRunObservations(
                jobIds, developerId, workspaceId, groupSlug);
        Set<UUID> visible =
                visibilityPolicy.permitsAll(workspaceId, found, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        List<Observation> observations =
                found.stream().filter(row -> visible.contains(row.getId())).toList();
        Map<UUID, List<Observation>> observationsByJob =
                observations.stream().collect(Collectors.groupingBy(Observation::getAgentJobId));
        Map<UUID, DeliveredFeedbackBinding> feedbackByObservation =
                feedbackByObservation(workspaceId, developerId, observations);
        Map<UUID, ReviewRunTargetLookup.Target> targets = reviewRunTargetLookup.findByJobIds(workspaceId, jobIds);

        List<PracticeGroupReviewRunDTO> reviewRuns = new ArrayList<>();
        for (ReviewRunRow run : runs) {
            List<Observation> visibleObservations = observationsByJob.getOrDefault(run.getJobId(), List.of());
            if (!visibleObservations.isEmpty()) {
                reviewRuns.add(toReviewRun(run, visibleObservations, feedbackByObservation, targets));
            }
        }
        return reviewRuns;
    }

    private Map<UUID, DeliveredFeedbackBinding> feedbackByObservation(
            Long workspaceId, Long recipientUserId, Collection<Observation> observations) {
        if (observations.isEmpty()) {
            return Map.of();
        }
        return feedbackObservationRepository
                .findDeliveredFeedbackBindings(
                        workspaceId,
                        recipientUserId,
                        observations.stream().map(Observation::getId).toList())
                .stream()
                .collect(Collectors.toMap(DeliveredFeedbackBinding::getObservationId, Function.identity()));
    }

    private PracticeGroupReviewRunDTO toReviewRun(
            ReviewRunRow run,
            List<Observation> observations,
            Map<UUID, DeliveredFeedbackBinding> feedbackByObservation,
            Map<UUID, ReviewRunTargetLookup.Target> targets) {
        Observation first = observations.getFirst();
        var target = targets.get(run.getJobId());
        PracticeGroupReviewedWorkDTO reviewedWork = target == null
                ? PracticeGroupReviewedWorkDTO.fallback(first.getArtifactKind(), first.getArtifactId())
                : PracticeGroupReviewedWorkDTO.from(target, first.getArtifactId());
        List<PracticeGroupReviewObservationDTO> reviewObservations = observations.stream()
                .map(observation -> {
                    DeliveredFeedbackBinding binding = feedbackByObservation.get(observation.getId());
                    return PracticeGroupReviewObservationDTO.from(
                            observation,
                            binding == null ? null : binding.getFeedbackId(),
                            usefulness(binding),
                            resolution(binding),
                            binding == null ? null : binding.getResponseComment());
                })
                .toList();
        return new PracticeGroupReviewRunDTO(run.getJobId(), run.getReviewedAt(), reviewedWork, reviewObservations);
    }

    private @Nullable FeedbackUsefulness usefulness(@Nullable DeliveredFeedbackBinding binding) {
        return binding == null || binding.getResponseUsefulness() == null
                ? null
                : FeedbackUsefulness.valueOf(binding.getResponseUsefulness());
    }

    private @Nullable FeedbackResolution resolution(@Nullable DeliveredFeedbackBinding binding) {
        return binding == null || binding.getResponseResolution() == null
                ? null
                : FeedbackResolution.valueOf(binding.getResponseResolution());
    }
}
