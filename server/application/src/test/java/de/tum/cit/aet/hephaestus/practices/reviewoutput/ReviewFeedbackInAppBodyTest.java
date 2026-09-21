package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyEvaluationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalRepository;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewFeedbackDetailDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReviewFeedbackInAppBodyTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 7L;

    private final FeedbackRepository feedbackRepository = mock(FeedbackRepository.class);
    private final FeedbackObservationRepository feedbackObservationRepository =
            mock(FeedbackObservationRepository.class);
    private final FeedbackPlacementRepository feedbackPlacementRepository = mock(FeedbackPlacementRepository.class);
    private final ReviewSubjectResolver subjectResolver = mock(ReviewSubjectResolver.class);
    private final ReviewRunTargetLookup reviewRunTargetLookup = mock(ReviewRunTargetLookup.class);
    private final FeedbackApprovalRepository approvalRepository = mock(FeedbackApprovalRepository.class);
    private final DeliveryPolicyEvaluationRepository policyEvaluationRepository =
            mock(DeliveryPolicyEvaluationRepository.class);

    private final ReviewFeedbackQueryService service = new ReviewFeedbackQueryService(
            feedbackRepository,
            feedbackObservationRepository,
            feedbackPlacementRepository,
            subjectResolver,
            reviewRunTargetLookup,
            approvalRepository,
            policyEvaluationRepository,
            JsonMapper.builder().build());

    @Test
    void withholdsAnInAppBodyFromTheOperatorDetailRoute() {
        ReviewFeedbackDetailDTO detail = detailFor(FeedbackChannel.IN_APP);

        assertThat(detail.body()).isNull();
        assertThat(detail.channel()).isEqualTo(FeedbackChannel.IN_APP);
        assertThat(detail.deliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED);
    }

    @Test
    void withholdsAConversationalMoveFromTheOperatorDetailRoute() {
        ReviewFeedbackDetailDTO detail = detailFor(FeedbackChannel.IN_CHAT);

        assertThat(detail.body()).isNull();
        assertThat(detail.channel()).isEqualTo(FeedbackChannel.IN_CHAT);
        assertThat(detail.deliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED);
    }

    @Test
    void returnsTheBodyForInContextFeedback() {
        assertThat(detailFor(FeedbackChannel.IN_CONTEXT).body()).isEqualTo("the composed text");
    }

    private ReviewFeedbackDetailDTO detailFor(FeedbackChannel channel) {
        UUID feedbackId = UUID.randomUUID();
        Feedback unit = Feedback.builder()
                .id(feedbackId)
                .agentJobId(UUID.randomUUID())
                .workspaceId(WORKSPACE_ID)
                .recipientUserId(11L)
                .aboutUserId(11L)
                .channel(channel)
                .position(0)
                .deliveryState(FeedbackDeliveryState.PREPARED)
                .source(FeedbackSource.AGENT)
                .body("the composed text")
                .createdAt(Instant.parse("2026-08-15T12:00:00Z"))
                .build();
        when(feedbackRepository.findByIdAndWorkspaceId(feedbackId, WORKSPACE_ID))
                .thenReturn(Optional.of(unit));
        when(feedbackObservationRepository.findBoundObservations(WORKSPACE_ID, feedbackId))
                .thenReturn(List.of());
        when(feedbackPlacementRepository.findByFeedbackIdInDisplayOrder(feedbackId))
                .thenReturn(List.of());
        when(policyEvaluationRepository.findByWorkspaceIdAndFeedbackIdOrderByEvaluatedAtAsc(WORKSPACE_ID, feedbackId))
                .thenReturn(List.of());
        when(policyEvaluationRepository
                        .findByWorkspaceIdAndAgentJobIdAndFeedbackIdIsNullAndSurfaceOrderByEvaluatedAtAsc(
                                WORKSPACE_ID,
                                unit.getAgentJobId(),
                                switch (channel) {
                                    case IN_CONTEXT ->
                                        de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface.ARTIFACT;
                                    case IN_APP ->
                                        de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface.IN_APP;
                                    case IN_CHAT ->
                                        de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface.CONVERSATION;
                                }))
                .thenReturn(List.of());
        when(approvalRepository.findByFeedbackIdAndWorkspaceId(feedbackId, WORKSPACE_ID))
                .thenReturn(Optional.empty());
        when(subjectResolver.resolve(any())).thenReturn(Map.of());
        return service.get(WORKSPACE_ID, feedbackId);
    }
}
