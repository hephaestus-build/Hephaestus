package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DiffNote;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.DeliveryStatus;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.DeliveredSignal;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.Disposition;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatch;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

class FeedbackDeliveryServiceTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 99L;

    @Mock
    private PullRequestCommentPoster commentPoster;

    @Mock
    private PracticeFeedbackDeliveryPolicy deliveryPolicy;

    @Mock
    private FeedbackLedgerRecorder ledgerRecorder;

    @Mock
    private PracticeFeedbackCommentFormatter commentFormatter;

    @Mock
    private PracticeFeedbackDispatchService dispatchService;

    @Mock
    private AgentJobRepository jobRepository;

    private FeedbackDeliveryService service;

    @BeforeEach
    void setUp() {
        service = new FeedbackDeliveryService(
                commentPoster, deliveryPolicy, ledgerRecorder, commentFormatter, dispatchService, jobRepository);
    }

    @Test
    void nullDeliveryStopsBeforePolicyOrDispatch() {
        service.deliverFeedback(job(), null);

        verifyNoInteractions(deliveryPolicy, dispatchService, ledgerRecorder);
    }

    @Test
    void policyRefusalIsRecordedWithoutCreatingADispatch() {
        AgentJob job = job();
        DeliveryContent delivery = delivery();
        when(deliveryPolicy.evaluatePullRequest(job, DeliveryPolicyStage.AUTOMATIC, null, Set.of("practice")))
                .thenReturn(PracticeFeedbackDeliveryPolicy.Decision.suppressed(
                        FeedbackSuppressionReason.RECIPIENT_OPTED_OUT));

        service.deliverFeedback(job, delivery, Set.of("practice"));

        verify(ledgerRecorder).recordSuppressedUnit(job, delivery, FeedbackSuppressionReason.RECIPIENT_OPTED_OUT);
        verifyNoInteractions(dispatchService);
    }

    @Test
    void allowedDeliveryHandsOneFormattedPackageToTheDispatcher() {
        AgentJob job = job();
        DeliveryContent delivery = delivery();
        allow(job, Set.of("practice"));
        when(commentFormatter.format("Summary", job)).thenReturn("Formatted summary");
        when(dispatchService.dispatchAutomaticPackage(eq(job), any(), eq(Set.of("practice"))))
                .thenReturn(PracticeFeedbackDispatchService.Result.sent("summary-1"));
        FeedbackDispatch dispatch = dispatchState(FeedbackDispatchState.SENT);
        when(dispatchService.automaticPackage(job)).thenReturn(dispatch);

        service.deliverFeedback(job, delivery, Set.of("practice"));

        var content = ArgumentCaptor.forClass(DeliveryContent.class);
        verify(dispatchService).dispatchAutomaticPackage(eq(job), content.capture(), eq(Set.of("practice")));
        assertThat(content.getValue().mrNote()).isEqualTo("Formatted summary");
        assertThat(content.getValue().diffNotes()).isEqualTo(delivery.diffNotes());
        assertThat(job.getDeliveryCommentId()).isEqualTo("summary-1");
    }

    @Test
    void nonterminalDispatchResultFailsTheJobForDurableRecovery() {
        AgentJob job = job();
        allow(job, Set.of());
        when(commentFormatter.format("Summary", job)).thenReturn("Formatted summary");
        when(dispatchService.dispatchAutomaticPackage(eq(job), any(), eq(Set.of())))
                .thenReturn(PracticeFeedbackDispatchService.Result.uncertain());
        FeedbackDispatch dispatch = dispatchState(FeedbackDispatchState.UNCERTAIN);
        when(dispatchService.automaticPackage(job)).thenReturn(dispatch);

        assertThatThrownBy(() -> service.deliverFeedback(job, delivery()))
                .isInstanceOf(JobDeliveryException.class)
                .hasMessageContaining("awaiting reconciliation");
    }

    @Test
    void sentProjectionRecordsTheExactDispatchReferenceAndReconcilesTheJob() {
        AgentJob job = job();
        FeedbackDispatch dispatch = projectableDispatch(FeedbackDispatchState.SENT, "dispatch-summary", null);
        DeliveryContent delivery = delivery();
        DeliveredSignal signal = signal("inline-1", "note-1");
        project(dispatch, delivery, List.of(signal));

        service.projectAutomaticPackage(job, dispatch);

        verify(ledgerRecorder)
                .record(job, delivery, ArtifactKinds.PULL_REQUEST, List.of(signal), "dispatch-summary", true);
        verify(jobRepository)
                .reconcileDispatchDeliveryStatus(
                        job.getId(), WORKSPACE_ID, DeliveryStatus.DELIVERED, "dispatch-summary");
    }

    @Test
    void fullySuppressedProjectionRecordsOneSuppressedUnit() {
        AgentJob job = job();
        FeedbackDispatch dispatch = projectableDispatch(
                FeedbackDispatchState.SUPPRESSED, null, FeedbackSuppressionReason.WORKSPACE_DELIVERY_PAUSED);
        DeliveryContent delivery = delivery();
        project(dispatch, delivery, List.of());

        service.projectAutomaticPackage(job, dispatch);

        verify(ledgerRecorder).recordSuppressedUnit(job, delivery, FeedbackSuppressionReason.WORKSPACE_DELIVERY_PAUSED);
        verify(ledgerRecorder, never())
                .recordWithoutConversation(any(), any(), any(), any(), nullable(String.class), anyBoolean());
        verify(jobRepository)
                .reconcileDispatchDeliveryStatus(job.getId(), WORKSPACE_ID, DeliveryStatus.DELIVERED, null);
    }

    @Test
    void partiallySuppressedProjectionKeepsDeliveredPlacementsAndNamesTheRemainder() {
        AgentJob job = job();
        FeedbackDispatch dispatch = projectableDispatch(
                FeedbackDispatchState.SUPPRESSED, "summary-1", FeedbackSuppressionReason.WORKSPACE_DELIVERY_PAUSED);
        DeliveryContent delivery = new DeliveryContent(
                "Summary",
                List.of(
                        new DiffNote("src/One.java", 10, null, "One", "inline-1"),
                        new DiffNote("src/Two.java", 20, null, "Two", "inline-2")),
                List.of());
        DeliveredSignal delivered = signal("inline-1", "note-1");
        project(dispatch, delivery, List.of(delivered));

        service.projectAutomaticPackage(job, dispatch);

        verify(ledgerRecorder)
                .recordWithoutConversation(
                        job, delivery, ArtifactKinds.PULL_REQUEST, List.of(delivered), "summary-1", true);
        verify(ledgerRecorder)
                .recordSuppressedRemainder(
                        job, delivery, FeedbackSuppressionReason.WORKSPACE_DELIVERY_PAUSED, List.of("inline-2"));
    }

    @Test
    void failedProjectionWithoutProviderWritesRecordsUndelivered() {
        AgentJob job = job();
        FeedbackDispatch dispatch = projectableDispatch(FeedbackDispatchState.FAILED, null, null);
        DeliveryContent delivery = delivery();
        project(dispatch, delivery, List.of());

        service.projectAutomaticPackage(job, dispatch);

        verify(ledgerRecorder).recordUndelivered(job, delivery);
        verify(jobRepository).reconcileDispatchDeliveryStatus(job.getId(), WORKSPACE_ID, DeliveryStatus.FAILED, null);
    }

    @Test
    void sameJobRecoveryProjectsTheTerminalPackageAndReusesItsReference() {
        AgentJob job = job();
        FeedbackDispatch dispatch = projectableDispatch(FeedbackDispatchState.SENT, "summary-1", null);
        DeliveryContent delivery = delivery();
        when(dispatchService.findAutomaticPackage(job)).thenReturn(Optional.of(dispatch));
        when(dispatchService.recover(dispatch, job))
                .thenReturn(PracticeFeedbackDispatchService.Result.sent("summary-1"));
        when(dispatchService.automaticPackage(job)).thenReturn(dispatch);
        project(dispatch, delivery, List.of());

        assertThat(service.recoverAutomaticPackageIfPresent(job)).isTrue();

        verify(ledgerRecorder).record(job, delivery, ArtifactKinds.PULL_REQUEST, List.of(), "summary-1", false);
        assertThat(job.getDeliveryCommentId()).isEqualTo("summary-1");
    }

    private void allow(AgentJob job, Set<String> practices) {
        when(deliveryPolicy.evaluatePullRequest(job, DeliveryPolicyStage.AUTOMATIC, null, practices))
                .thenReturn(PracticeFeedbackDeliveryPolicy.Decision.allowed(new PullRequest()));
    }

    private void project(FeedbackDispatch dispatch, DeliveryContent delivery, List<DeliveredSignal> signals) {
        when(dispatchService.packageContent(dispatch)).thenReturn(delivery);
        when(dispatchService.deliveredSignals(dispatch)).thenReturn(signals);
        when(dispatchService.projectRecovered(eq(dispatch), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return true;
        });
    }

    private FeedbackDispatch dispatchState(FeedbackDispatchState state) {
        FeedbackDispatch dispatch = mock(FeedbackDispatch.class);
        when(dispatch.getState()).thenReturn(state);
        return dispatch;
    }

    private FeedbackDispatch projectableDispatch(
            FeedbackDispatchState state, @Nullable String externalRef, @Nullable FeedbackSuppressionReason reason) {
        FeedbackDispatch dispatch = dispatchState(state);
        when(dispatch.getDeliveredExternalRef()).thenReturn(externalRef);
        if (reason != null) when(dispatch.getSuppressionReason()).thenReturn(reason.name());
        when(dispatch.getAgentJobId()).thenReturn(jobId());
        when(dispatch.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        return dispatch;
    }

    private AgentJob job() {
        AgentJob job = new AgentJob();
        job.setId(jobId());
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        job.setWorkspace(workspace);
        return job;
    }

    private UUID jobId() {
        return UUID.fromString("00000000-0000-0000-0000-000000000123");
    }

    private DeliveryContent delivery() {
        return new DeliveryContent(
                "Summary", List.of(new DiffNote("src/App.java", 10, null, "Inline", "inline-1")), List.of());
    }

    private DeliveredSignal signal(String recurrenceKey, String externalRef) {
        return new DeliveredSignal(
                recurrenceKey,
                new FeedbackAnchor.DiffAnchor("src/App.java", 10, null),
                Disposition.POSTED,
                externalRef,
                "discussion-1");
    }
}
