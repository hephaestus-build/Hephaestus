package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyEvaluationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackQueryFilter;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository.OperatorFeedbackRow;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.dto.FeedbackApprovalDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewBoundObservationDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewFeedbackDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewFeedbackDetailDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewPlacementDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewSubjectDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup.Target;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import de.tum.cit.aet.hephaestus.practices.trace.dto.DeliveryPolicyTraceDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
class ReviewFeedbackQueryService {

    private final FeedbackRepository feedbackRepository;
    private final FeedbackObservationRepository feedbackObservationRepository;
    private final FeedbackPlacementRepository feedbackPlacementRepository;
    private final ReviewSubjectResolver subjectResolver;
    private final ReviewRunTargetLookup reviewRunTargetLookup;
    private final FeedbackApprovalRepository approvalRepository;
    private final DeliveryPolicyEvaluationRepository policyEvaluations;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Page<ReviewFeedbackDTO> list(Long workspaceId, FeedbackQueryFilter filter, Pageable pageable) {
        Page<OperatorFeedbackRow> rows = feedbackRepository.findForWorkspace(workspaceId, filter, pageable);
        List<Long> userIds = new ArrayList<>(rows.getNumberOfElements() * 2);
        for (OperatorFeedbackRow row : rows) {
            userIds.add(row.getRecipientUserId());
            userIds.add(row.getAboutUserId());
        }
        Map<Long, ReviewSubjectDTO> subjects = subjectResolver.resolve(userIds);
        Map<UUID, Target> targets = reviewRunTargetLookup.findByJobIds(
                workspaceId,
                rows.getContent().stream()
                        .map(OperatorFeedbackRow::getAgentJobId)
                        .toList());
        return rows.map(row -> {
            String artifactKind = row.getArtifactKind();
            Long artifactId = row.getArtifactId();
            ReviewedWorkRefDTO artifact = artifactKind == null || artifactId == null
                    ? null
                    : ReviewedWorkLabels.ref(
                            ArtifactKind.of(artifactKind), artifactId, targets.get(row.getAgentJobId()));
            return ReviewFeedbackDTO.from(row, artifact, subjects);
        });
    }

    @Transactional(readOnly = true)
    public ReviewFeedbackDetailDTO get(Long workspaceId, UUID feedbackId) {
        Feedback feedback = feedbackRepository
                .findByIdAndWorkspaceId(feedbackId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Feedback", feedbackId.toString()));
        List<ReviewBoundObservationDTO> observations =
                feedbackObservationRepository.findBoundObservations(workspaceId, feedbackId).stream()
                        .map(ReviewBoundObservationDTO::from)
                        .toList();
        List<ReviewPlacementDTO> placements =
                feedbackPlacementRepository.findByFeedbackIdInDisplayOrder(feedbackId).stream()
                        .map(ReviewPlacementDTO::from)
                        .toList();
        Map<Long, ReviewSubjectDTO> subjects =
                subjectResolver.resolve(List.of(feedback.getRecipientUserId(), feedback.getAboutUserId()));
        ArtifactKind artifactKind = feedback.getArtifactKind();
        Long artifactId = feedback.getArtifactId();
        ReviewedWorkRefDTO artifact = artifactKind == null || artifactId == null
                ? null
                : ReviewedWorkLabels.ref(
                        artifactKind,
                        artifactId,
                        reviewRunTargetLookup
                                .findByJobIds(workspaceId, List.of(feedback.getAgentJobId()))
                                .get(feedback.getAgentJobId()));
        var evaluations =
                policyEvaluations.findByWorkspaceIdAndFeedbackIdOrderByEvaluatedAtAsc(workspaceId, feedbackId);
        if (evaluations.isEmpty()) {
            evaluations =
                    policyEvaluations.findByWorkspaceIdAndAgentJobIdAndFeedbackIdIsNullAndSurfaceOrderByEvaluatedAtAsc(
                            workspaceId, feedback.getAgentJobId(), surfaceFor(feedback.getChannel()));
        }
        List<DeliveryPolicyTraceDTO> deliveryPolicy = evaluations.stream()
                .map(evaluation -> DeliveryPolicyTraceDTO.from(evaluation, objectMapper))
                .toList();
        FeedbackApprovalDTO approval = approvalRepository
                .findByFeedbackIdAndWorkspaceId(feedbackId, workspaceId)
                .map(FeedbackApprovalDTO::from)
                .orElse(null);
        return ReviewFeedbackDetailDTO.from(
                feedback,
                artifact,
                subjects.get(feedback.getRecipientUserId()),
                subjects.get(feedback.getAboutUserId()),
                observations,
                placements,
                approval,
                deliveryPolicy,
                bodyVisibleToOperator(feedback));
    }

    private static DeliveryPolicySurface surfaceFor(FeedbackChannel channel) {
        return switch (channel) {
            case IN_CONTEXT -> DeliveryPolicySurface.ARTIFACT;
            case IN_APP -> DeliveryPolicySurface.IN_APP;
            case IN_CHAT -> DeliveryPolicySurface.CONVERSATION;
        };
    }

    private static boolean bodyVisibleToOperator(Feedback feedback) {
        return (feedback.getChannel() != FeedbackChannel.IN_APP && feedback.getChannel() != FeedbackChannel.IN_CHAT);
    }
}
