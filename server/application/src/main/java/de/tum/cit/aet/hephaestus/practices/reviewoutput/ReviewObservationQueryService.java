package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountSummaryQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountSummaryQuery.AccountSummary;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository.ObservationFeedbackCounts;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationInvalidation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationInvalidationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationQueryFilter;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.OperatorObservationRow;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ObservationInvalidationDTO;
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
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
    private final FeedbackRepository feedbackRepository;
    private final ReviewSubjectResolver subjectResolver;
    private final ReviewRunTargetLookup reviewRunTargetLookup;
    private final EvidenceAuthorization evidenceAuthorization;
    private final ObservationInvalidationRepository invalidationRepository;
    private final AccountSummaryQuery accountSummaryQuery;

    @Transactional(readOnly = true)
    public Page<ReviewObservationDTO> list(
            Long workspaceId, ObservationQueryFilter filter, ReviewObservationSort sort, Pageable pageable) {
        Page<OperatorObservationRow> rows = observationRepository.findForWorkspace(
                workspaceId, filter, sort == ReviewObservationSort.ACTIONABILITY, pageable);
        Map<Long, ReviewSubjectDTO> subjects = subjectResolver.resolve(rows.getContent().stream()
                .map(OperatorObservationRow::getAboutUserId)
                .toList());
        Map<UUID, ObservationFeedbackCounts> feedbackCounts = rows.isEmpty()
                ? Map.of()
                : feedbackRepository
                        .summarizeFeedbackByObservation(
                                workspaceId,
                                rows.getContent().stream()
                                        .map(OperatorObservationRow::getId)
                                        .toList())
                        .stream()
                        .collect(Collectors.toMap(ObservationFeedbackCounts::getObservationId, Function.identity()));
        Map<UUID, Target> targets = reviewRunTargetLookup.findByJobIds(
                workspaceId,
                rows.getContent().stream()
                        .map(OperatorObservationRow::getAgentJobId)
                        .toList());
        return rows.map(row -> ReviewObservationDTO.from(
                row,
                feedbackCounts.get(row.getId()),
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
        List<ObservationInvalidation> history = invalidationRepository.findHistory(workspaceId, observationId);
        Map<Long, AccountSummary> accounts = accountSummaryQuery.findAllByIds(history.stream()
                .flatMap(invalidation ->
                        Stream.of(invalidation.getInvalidatedByAccountId(), invalidation.getRestoredByAccountId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        List<ObservationInvalidationDTO> invalidations = history.stream()
                .map(invalidation -> ObservationInvalidationDTO.from(invalidation, accounts))
                .toList();
        return ReviewObservationDetailDTO.from(
                observation, reviewedWork, subject, feedback, invalidations, includeEvidence);
    }
}
