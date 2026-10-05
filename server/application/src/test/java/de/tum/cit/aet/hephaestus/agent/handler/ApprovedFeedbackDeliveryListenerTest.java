package de.tum.cit.aet.hephaestus.agent.handler;

import static de.tum.cit.aet.hephaestus.testconfig.TestEntities.agentJob;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
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
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("unit")
class ApprovedFeedbackDeliveryListenerTest {

    private static final UUID LINE_NOTES_ID = UUID.fromString("6c1f0e2a-5b3d-4a7e-9f80-1d2c3b4a5e6f");
    private static final ProposedPlacement INLINE_PLACEMENT =
            ProposedPlacement.inline("Name the failure case", "src/Review.java", 12, null, "review-key");
    private static final InlineFeedbackChannel.DeliveredSignal LANDED =
            lineSignal(12, InlineFeedbackChannel.Disposition.POSTED, "note-12");
    private static final InlineFeedbackChannel.DeliveredSignal NOT_LANDED =
            lineSignal(20, InlineFeedbackChannel.Disposition.FAILED, null);

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

    @Test
    void shouldDispatchAnApprovedProposalOfLineNotesAloneAndMarkItDeliveredWhenEveryNoteLands() {
        Fixture fixture = fixture(lineNotesProposal(INLINE_PLACEMENT));
        allow(fixture);
        var sent = PracticeFeedbackDispatchService.Result.sent(null, null, List.of(LANDED));
        when(fixture.dispatchService().dispatchApproved(fixture.job(), fixture.feedback()))
                .thenReturn(sent);

        fixture.listener().deliver(event(fixture.feedback()));

        verify(fixture.ledger()).recordApprovedPlacements(fixture.feedback(), null, null, List.of(LANDED));
        verify(fixture.feedbackRepository()).markApprovedDelivered(7L, LINE_NOTES_ID);
    }

    @Test
    void shouldNotDispatchAProposalWithNeitherASummaryNorALineNote() {
        Fixture fixture = fixture(lineNotesProposal(ProposedPlacement.inline(" ", "src/Review.java", 12, null, "k")));

        fixture.listener().deliver(event(fixture.feedback()));

        verifyNoInteractions(fixture.dispatchService(), fixture.ledger());
        verify(fixture.feedbackRepository()).findByIdAndWorkspaceId(LINE_NOTES_ID, 7L);
        verifyNoMoreInteractions(fixture.feedbackRepository());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("outcomesWithALandedLineNoteAndNoSummary")
    void shouldKeepALandedLineNoteAsPartialDeliveryWithoutASummary(
            PracticeFeedbackDispatchService.Result result, Consumer<FeedbackRepository> partialTransition) {
        Fixture fixture = fixture(lineNotesProposal(INLINE_PLACEMENT));
        allow(fixture);
        when(fixture.dispatchService().dispatchApproved(fixture.job(), fixture.feedback()))
                .thenReturn(result);

        fixture.listener().deliver(event(fixture.feedback()));

        verify(fixture.ledger()).recordApprovedPlacements(fixture.feedback(), null, null, result.deliveredSignals());
        verify(fixture.feedbackRepository()).findByIdAndWorkspaceId(LINE_NOTES_ID, 7L);
        partialTransition.accept(verify(fixture.feedbackRepository()));
        verifyNoMoreInteractions(fixture.feedbackRepository());
    }

    static Stream<Arguments> outcomesWithALandedLineNoteAndNoSummary() {
        return Stream.of(
                arguments(
                        PracticeFeedbackDispatchService.Result.suppressed(
                                FeedbackSuppressionReason.APPROVAL_STALE, null, null, List.of(LANDED, NOT_LANDED)),
                        (Consumer<FeedbackRepository>) repository -> repository.markApprovedPartiallyDelivered(
                                7L, LINE_NOTES_ID, FeedbackSuppressionReason.APPROVAL_STALE.name())),
                arguments(
                        PracticeFeedbackDispatchService.Result.failed(null, null, List.of(LANDED, NOT_LANDED)),
                        (Consumer<FeedbackRepository>)
                                repository -> repository.markApprovedPartiallyFailed(7L, LINE_NOTES_ID)),
                arguments(
                        PracticeFeedbackDispatchService.Result.uncertain(null, null, List.of(LANDED, NOT_LANDED)),
                        (Consumer<FeedbackRepository>)
                                repository -> repository.markApprovedPartiallyDelivered(7L, LINE_NOTES_ID, null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("settledOutcomesWithOnlyAFailedLineNote")
    void shouldNotCountAFailedLineNoteAsDelivered(
            PracticeFeedbackDispatchService.Result result, Consumer<FeedbackRepository> transition) {
        Fixture fixture = fixture(lineNotesProposal(INLINE_PLACEMENT));
        allow(fixture);
        when(fixture.dispatchService().dispatchApproved(fixture.job(), fixture.feedback()))
                .thenReturn(result);

        fixture.listener().deliver(event(fixture.feedback()));

        verify(fixture.feedbackRepository()).findByIdAndWorkspaceId(LINE_NOTES_ID, 7L);
        transition.accept(verify(fixture.feedbackRepository()));
        verifyNoMoreInteractions(fixture.feedbackRepository());
    }

    static Stream<Arguments> settledOutcomesWithOnlyAFailedLineNote() {
        return Stream.of(
                arguments(
                        PracticeFeedbackDispatchService.Result.suppressed(
                                FeedbackSuppressionReason.APPROVAL_STALE, null, null, List.of(NOT_LANDED)),
                        (Consumer<FeedbackRepository>) repository -> repository.markApprovedSuppressed(
                                7L, LINE_NOTES_ID, FeedbackSuppressionReason.APPROVAL_STALE.name())),
                arguments(
                        PracticeFeedbackDispatchService.Result.failed(null, null, List.of(NOT_LANDED)),
                        (Consumer<FeedbackRepository>) repository -> repository.markApprovedFailed(7L, LINE_NOTES_ID)));
    }

    @Test
    void shouldLeaveTheProposalPreparedWhileAnUnsettledAttemptHasOnlyAFailedLineNote() {
        Fixture fixture = fixture(lineNotesProposal(INLINE_PLACEMENT));
        allow(fixture);
        when(fixture.dispatchService().dispatchApproved(fixture.job(), fixture.feedback()))
                .thenReturn(PracticeFeedbackDispatchService.Result.uncertain(null, null, List.of(NOT_LANDED)));

        fixture.listener().deliver(event(fixture.feedback()));

        verify(fixture.feedbackRepository()).findByIdAndWorkspaceId(LINE_NOTES_ID, 7L);
        verifyNoMoreInteractions(fixture.feedbackRepository());
        verifyNoInteractions(fixture.ledger());
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
        return fixture(proposal(UUID.randomUUID(), UUID.randomUUID(), "Exact proposal"));
    }

    private static Fixture fixture(Feedback feedback) {
        FeedbackRepository feedbackRepository = mock(FeedbackRepository.class);
        FeedbackApprovalRepository approvalRepository = mock(FeedbackApprovalRepository.class);
        AgentJobRepository jobRepository = mock(AgentJobRepository.class);
        PracticeFeedbackDeliveryPolicy policy = mock(PracticeFeedbackDeliveryPolicy.class);
        PracticeFeedbackDispatchService dispatchService = mock(PracticeFeedbackDispatchService.class);
        FeedbackApprovalEligibility eligibility = mock(FeedbackApprovalEligibility.class);
        FeedbackLedgerRecorder ledger = mock(FeedbackLedgerRecorder.class);
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
        lenient().when(dispatchService.projectApproved(any(), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return true;
        });
        ApprovedFeedbackDeliveryListener listener = new ApprovedFeedbackDeliveryListener(
                feedbackRepository, approvalRepository, jobRepository, policy, dispatchService, eligibility, ledger);
        return new Fixture(
                listener,
                feedbackRepository,
                approvalRepository,
                policy,
                dispatchService,
                eligibility,
                ledger,
                feedback,
                job);
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

    private static Feedback lineNotesProposal(ProposedPlacement placement) {
        return Feedback.builder()
                .id(LINE_NOTES_ID)
                .agentJobId(UUID.randomUUID())
                .workspaceId(7L)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .recipientUserId(8L)
                .aboutUserId(8L)
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(7_000)
                .deliveryState(FeedbackDeliveryState.PREPARED)
                .proposedPlacements(new ArrayList<>(List.of(placement)))
                .proposedPracticeSlugs(new ArrayList<>(List.of("review-quality")))
                .source(FeedbackSource.AGENT)
                .build();
    }

    private static InlineFeedbackChannel.DeliveredSignal lineSignal(
            int line, InlineFeedbackChannel.Disposition disposition, @Nullable String externalRef) {
        return new InlineFeedbackChannel.DeliveredSignal(
                "approved:" + LINE_NOTES_ID + ":" + line,
                FeedbackAnchor.DiffAnchor.singleLine("src/Review.java", line),
                disposition,
                externalRef,
                null);
    }

    private record Fixture(
            ApprovedFeedbackDeliveryListener listener,
            FeedbackRepository feedbackRepository,
            FeedbackApprovalRepository approvalRepository,
            PracticeFeedbackDeliveryPolicy policy,
            PracticeFeedbackDispatchService dispatchService,
            FeedbackApprovalEligibility eligibility,
            FeedbackLedgerRecorder ledger,
            Feedback feedback,
            AgentJob job) {}
}
