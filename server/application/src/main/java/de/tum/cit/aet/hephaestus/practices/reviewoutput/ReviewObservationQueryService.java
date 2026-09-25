package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationQueryFilter;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.ObservationFeedbackDisposition;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.OperatorObservationRow;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewBoundFeedbackDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewObservationDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewObservationDetailDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewSubjectDTO;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup.Target;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class ReviewObservationQueryService {

    private final ObservationRepository observationRepository;
    private final FeedbackObservationRepository feedbackObservationRepository;
    private final ReviewSubjectResolver subjectResolver;
    private final ReviewRunTargetLookup reviewRunTargetLookup;
    private final EvidenceAuthorization evidenceAuthorization;

    @Transactional(readOnly = true)
    public Page<ReviewObservationDTO> list(
            Long workspaceId, ObservationQueryFilter filter, ReviewObservationSort sort, Pageable pageable) {
        Page<OperatorObservationRow> rows = observationRepository.findForWorkspace(
                workspaceId, filter, sort == ReviewObservationSort.ACTIONABILITY, pageable);
        Map<Long, ReviewSubjectDTO> subjects = subjectResolver.resolve(rows.getContent().stream()
                .map(OperatorObservationRow::getAboutUserId)
                .toList());
        Map<UUID, ObservationFeedbackDisposition> dispositions = rows.isEmpty()
                ? Map.of()
                : observationRepository
                        .findFeedbackDispositions(
                                workspaceId,
                                rows.getContent().stream()
                                        .map(OperatorObservationRow::getId)
                                        .toList())
                        .stream()
                        .collect(Collectors.toMap(
                                ObservationFeedbackDisposition::getObservationId, Function.identity()));
        Map<UUID, Target> targets = reviewRunTargetLookup.findByJobIds(
                workspaceId,
                rows.getContent().stream()
                        .map(OperatorObservationRow::getAgentJobId)
                        .toList());
        return rows.map(row -> ReviewObservationDTO.from(
                row,
                dispositions.get(row.getId()),
                ReviewedWorkLabels.ref(
                        ArtifactKind.of(row.getArtifactKind()), row.getArtifactId(), targets.get(row.getAgentJobId())),
                subjects));
    }

    @Transactional(readOnly = true)
    public ReviewObservationDetailDTO get(Long workspaceId, UUID observationId) {
        Observation observation = observationRepository
                .findByIdAndWorkspaceId(observationId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Observation", observationId.toString()));
        List<ReviewBoundFeedbackDTO> feedback =
                feedbackObservationRepository.findBoundFeedbackUnits(workspaceId, observationId).stream()
                        .map(ReviewBoundFeedbackDTO::from)
                        .toList();
        ReviewSubjectDTO subject =
                subjectResolver.resolve(List.of(observation.getAboutUserId())).get(observation.getAboutUserId());
        ReviewedWorkRefDTO reviewedWork = ReviewedWorkLabels.ref(
                observation.getArtifactKind(),
                observation.getArtifactId(),
                reviewRunTargetLookup
                        .findByJobIds(workspaceId, List.of(observation.getAgentJobId()))
                        .get(observation.getAgentJobId()));
        boolean includeEvidence =
                evidenceAuthorization.permits(workspaceId, observation, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW);
        return ReviewObservationDetailDTO.from(observation, reviewedWork, subject, feedback, includeEvidence);
    }
}
