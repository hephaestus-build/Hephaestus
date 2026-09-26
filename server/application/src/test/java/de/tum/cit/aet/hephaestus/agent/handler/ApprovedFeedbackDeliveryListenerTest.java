package de.tum.cit.aet.hephaestus.agent.handler;

import static de.tum.cit.aet.hephaestus.testconfig.TestEntities.agentJob;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.ProposedPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.ApprovedFeedbackReadyEvent;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApproval;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalDigest;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalEligibility;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ApprovedFeedbackDeliveryListenerTest {

    @Test
    void suppressesWhenContributingPracticeNoLongerRequiresApproval() {
        Fixture fixture = fixture();
        when(fixture.eligibility().isEligible(7L, fixture.feedback().getId())).thenReturn(false);

        fixture.listener().deliver(event(fixture.feedback()));

        verify(fixture.feedbackRepository())
                .markApprovedSuppressed(
                        7L, fixture.feedback().getId(), FeedbackSuppressionReason.APPROVAL_NO_LONGER_ELIGIBLE.name());
        verifyNoInteractions(fixture.dispatchService());
    }

    @Test
    void appliesCurrentApprovedStagePolicyBeforeDispatch() {
        Fixture fixture = fixture();
        when(fixture.policy()
                        .evaluatePullRequest(
                                fixture.job(),
                                DeliveryPolicyStage.APPROVED,
                                fixture.feedback().getId(),
                                fixture.feedback().getProposedPracticeSlugs()))
                .thenReturn(PracticeFeedbackDeliveryPolicy.Decision.suppressed(
                        FeedbackSuppressionReason.RECIPIENT_OPTED_OUT));

        fixture.listener().deliver(event(fixture.feedback()));

        verify(fixture.feedbackRepository())
                .markApprovedSuppressed(
                        7L, fixture.feedback().getId(), FeedbackSuppressionReason.RECIPIENT_OPTED_OUT.name());
        verifyNoInteractions(fixture.dispatchService());
    }

    @Test
    void marksDeliveredOnlyAfterSharedDispatchConfirmsSent() {
        Fixture fixture = fixture();
        allow(fixture);
        when(fixture.dispatchService().dispatchApproved(fixture.job(), fixture.feedback()))
                .thenReturn(PracticeFeedbackDispatchService.Result.sent("provider-id"));

        fixture.listener().deliver(event(fixture.feedback()));

        verify(fixture.feedbackRepository())
                .markApprovedDelivered(7L, fixture.feedback().getId());
    }

    @Test
    void sendsNothingWhenThePullRequestMovedAfterTheReviewedRevision() {
        Fixture fixture = fixture();
        PullRequest current = new PullRequest();
        current.setHeadRefOid("new-head");
        when(fixture.policy()
                        .evaluatePullRequest(
                                fixture.job(),
                                DeliveryPolicyStage.APPROVED,
                                fixture.feedback().getId(),
                                List.of()))
                .thenReturn(PracticeFeedbackDeliveryPolicy.Decision.allowed(current));
        Feedback stale = Feedback.builder()
                .id(fixture.feedback().getId())
                .agentJobId(fixture.feedback().getAgentJobId())
                .workspaceId(7L)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .recipientUserId(8L)
                .aboutUserId(8L)
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(7_000)
                .deliveryState(FeedbackDeliveryState.PREPARED)
                .body("Exact proposal")
                .reviewedRevision("old-head")
                .source(FeedbackSource.AGENT)
                .build();
        when(fixture.feedbackRepository().findByIdAndWorkspaceId(stale.getId(), 7L))
                .thenReturn(Optional.of(stale));
        when(fixture.approvalRepository().findByFeedbackIdAndWorkspaceId(stale.getId(), 7L))
                .thenReturn(Optional.of(FeedbackApproval.builder()
                        .feedbackId(stale.getId())
                        .workspaceId(7L)
                        .contentDigest(FeedbackApprovalDigest.of(stale))
                        .build()));

        fixture.listener().deliver(event(stale));

        verify(fixture.feedbackRepository())
                .markApprovedSuppressed(7L, stale.getId(), FeedbackSuppressionReason.APPROVAL_STALE.name());
        verifyNoInteractions(fixture.dispatchService());
    }

    @Test
    void leavesProposalPreparedWhileDispatchIsUncertain() {
        Fixture fixture = fixture();
        allow(fixture);
        when(fixture.dispatchService().dispatchApproved(fixture.job(), fixture.feedback()))
                .thenReturn(PracticeFeedbackDispatchService.Result.uncertain(null));

        fixture.listener().deliver(event(fixture.feedback()));

        verify(fixture.feedbackRepository(), never()).markApprovedDelivered(anyLong(), any());
        verify(fixture.feedbackRepository(), never()).markApprovedSuppressed(anyLong(), any(), any());
    }

    @Test
    void persistsTheDispatchEgressSuppressionReason() {
        Fixture fixture = fixture();
        allow(fixture);
        when(fixture.dispatchService().dispatchApproved(fixture.job(), fixture.feedback()))
                .thenReturn(PracticeFeedbackDispatchService.Result.suppressed(
                        FeedbackSuppressionReason.WORKSPACE_DELIVERY_PAUSED));

        fixture.listener().deliver(event(fixture.feedback()));

        verify(fixture.feedbackRepository())
                .markApprovedSuppressed(
                        7L, fixture.feedback().getId(), FeedbackSuppressionReason.WORKSPACE_DELIVERY_PAUSED.name());
    }

    @Test
    void suppressesWhenApprovedContentNoLongerMatches() {
        Fixture fixture = fixture();
        when(fixture.approvalRepository()
                        .findByFeedbackIdAndWorkspaceId(fixture.feedback().getId(), 7L))
                .thenReturn(Optional.of(FeedbackApproval.builder()
                        .feedbackId(fixture.feedback().getId())
                        .workspaceId(7L)
                        .contentDigest("0".repeat(64))
                        .build()));

        fixture.listener().deliver(event(fixture.feedback()));

        verify(fixture.feedbackRepository())
                .markApprovedSuppressed(
                        7L, fixture.feedback().getId(), FeedbackSuppressionReason.APPROVAL_STALE.name());
        verifyNoInteractions(fixture.dispatchService());
    }

    private static void allow(Fixture fixture) {
        when(fixture.policy()
                        .evaluatePullRequest(
                                fixture.job(),
                                DeliveryPolicyStage.APPROVED,
                                fixture.feedback().getId(),
                                fixture.feedback().getProposedPracticeSlugs()))
                .thenReturn(PracticeFeedbackDeliveryPolicy.Decision.allowed(new PullRequest()));
    }

    private static Fixture fixture() {
        FeedbackRepository feedbackRepository = mock(FeedbackRepository.class);
        FeedbackApprovalRepository approvalRepository = mock(FeedbackApprovalRepository.class);
        AgentJobRepository jobRepository = mock(AgentJobRepository.class);
        PracticeFeedbackDeliveryPolicy policy = mock(PracticeFeedbackDeliveryPolicy.class);
        PracticeFeedbackDispatchService dispatchService = mock(PracticeFeedbackDispatchService.class);
        FeedbackApprovalEligibility eligibility = mock(FeedbackApprovalEligibility.class);
        Feedback feedback = proposal(UUID.randomUUID(), UUID.randomUUID(), "Exact proposal");
        AgentJob job = agentJob();
        when(feedbackRepository.findByIdAndWorkspaceId(feedback.getId(), 7L)).thenReturn(Optional.of(feedback));
        when(approvalRepository.findByFeedbackIdAndWorkspaceId(feedback.getId(), 7L))
                .thenReturn(Optional.of(FeedbackApproval.builder()
                        .feedbackId(feedback.getId())
                        .workspaceId(7L)
                        .contentDigest(FeedbackApprovalDigest.of(feedback))
                        .build()));
        when(eligibility.isEligible(7L, feedback.getId())).thenReturn(true);
        when(jobRepository.findByIdAndWorkspaceId(feedback.getAgentJobId(), 7L)).thenReturn(Optional.of(job));
        org.mockito.Mockito.lenient()
                .when(dispatchService.projectApproved(any(), any()))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return true;
                });
        ApprovedFeedbackDeliveryListener listener = new ApprovedFeedbackDeliveryListener(
                feedbackRepository,
                approvalRepository,
                jobRepository,
                policy,
                dispatchService,
                eligibility,
                mock(FeedbackLedgerRecorder.class));
        return new Fixture(
                listener, feedbackRepository, approvalRepository, policy, dispatchService, eligibility, feedback, job);
    }

    private static ApprovedFeedbackReadyEvent event(Feedback feedback) {
        return new ApprovedFeedbackReadyEvent(7L, feedback.getId());
    }

    private static Feedback proposal(UUID feedbackId, UUID jobId, String body) {
        return Feedback.builder()
                .id(feedbackId)
                .agentJobId(jobId)
                .workspaceId(7L)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .recipientUserId(8L)
                .aboutUserId(8L)
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(7_000)
                .deliveryState(FeedbackDeliveryState.PREPARED)
                .body(body)
                .proposedPlacements(new ArrayList<>(List.of(ProposedPlacement.summary(body))))
                .proposedPracticeSlugs(new ArrayList<>(List.of("review-quality")))
                .source(FeedbackSource.AGENT)
                .build();
    }

    private record Fixture(
            ApprovedFeedbackDeliveryListener listener,
            FeedbackRepository feedbackRepository,
            FeedbackApprovalRepository approvalRepository,
            PracticeFeedbackDeliveryPolicy policy,
            PracticeFeedbackDispatchService dispatchService,
            FeedbackApprovalEligibility eligibility,
            Feedback feedback,
            AgentJob job) {}
}
