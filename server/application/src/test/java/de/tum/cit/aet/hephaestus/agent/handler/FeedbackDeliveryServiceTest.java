package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DiffNote;
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
        AgentJob job = job();

        var withheldOnly = new DeliveryContent(
                null,
                List.of(),
                List.of(new ReviewResultParser.WithheldObservation(
                        "occ-1", FeedbackSuppressionReason.COMPOSER_WITHHELD)),
                List.of());

        service.deliverFeedback(job, null);
        service.deliverFeedback(job, withheldOnly, Set.of());

        verifyNoInteractions(deliveryPolicy, dispatchService);
        verify(ledgerRecorder).recordNothingToPost(job, null);
        verify(ledgerRecorder).recordNothingToPost(job, withheldOnly);
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
    void shouldHandTheComposedReviewToTheDispatcherWordForWordWhenOnlySecurityMarkupNeedsEscaping() {
        AgentJob job = job();
        allow(job, Set.of("practice"));
        // Sentences an earlier sanitizer deleted or rewrote: a reference to the practice, an approval-shaped
        // phrase inside a longer thought, a numeric bound. Each is part of the composed argument.
        String paragraphs = "The practice requires the description to say why; this one says what changed only.\n\n"
                + "Before this is ready for another look, add one sentence on the reason for the new screen.\n\n"
                + "```swift\nlet fill = Color(red: 0.2, green: 0.6, blue: 0.3)\n```\n\n"
                + "The diff touches <= 3 files, so a short reason is enough.";
        String note = "This explicit RGB fill stays the same in Dark Mode.\n\nCheck its contrast in both appearances.";
        var composed = new DeliveryContent(
                paragraphs + " Thanks @alice for the preview.",
                List.of(new DiffNote("App/ContentView.swift", 13, null, note, "observation:occ-1#0", List.of("occ-1"))),
                List.of(),
                List.of("occ-1"));
        when(commentFormatter.format(paragraphs + " Thanks `@alice` for the preview.", job))
                .thenReturn("Formatted review");
        when(dispatchService.dispatchAutomaticPackage(eq(job), any(), eq(Set.of("practice"))))
                .thenReturn(PracticeFeedbackDispatchService.Result.sent("summary-1"));
        FeedbackDispatch dispatch = dispatchState(FeedbackDispatchState.SENT);
        when(dispatchService.automaticPackage(job)).thenReturn(dispatch);

        service.deliverFeedback(job, composed, Set.of("practice"));

        var content = ArgumentCaptor.forClass(DeliveryContent.class);
        verify(dispatchService).dispatchAutomaticPackage(eq(job), content.capture(), eq(Set.of("practice")));
        assertThat(content.getValue().mrNote()).isEqualTo("Formatted review");
        assertThat(content.getValue().diffNotes()).singleElement().satisfies(diff -> {
            assertThat(diff.body()).isEqualTo(note);
            assertThat(diff.deliveryKey()).isEqualTo("observation:occ-1#0");
        });
        assertThat(PullRequestCommentPoster.sanitize(note)).isEqualTo(note);
    }

    @Test
    void shouldRecordSuppressionWhenSanitizationLeavesNoProviderContent() {
        AgentJob job = job();
        allow(job, Set.of("practice"));
        var composed = new DeliveryContent("<iframe></iframe>", List.of(), List.of(), List.of("occ-1"));

        service.deliverFeedback(job, composed, Set.of("practice"));

        verify(ledgerRecorder).recordSuppressedUnit(job, composed, FeedbackSuppressionReason.EMPTY_AFTER_SANITIZE);
        verifyNoInteractions(dispatchService, commentFormatter);
        assertThat(job.getDeliveryCommentId()).isNull();
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
                .hasMessageContaining("waits for reconciliation");
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
                .record(job, delivery, ArtifactKinds.PULL_REQUEST, List.of(signal), "dispatch-summary", null);
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
                .recordWithoutConversation(any(), any(), any(), any(), nullable(String.class), nullable(String.class));
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
                        new DiffNote("src/One.java", 10, null, "One", "inline-1", null),
                        new DiffNote("src/Two.java", 20, null, "Two", "inline-2", null)),
                List.of(),
                null);
        DeliveredSignal delivered = signal("inline-1", "note-1");
        project(dispatch, delivery, List.of(delivered));

        service.projectAutomaticPackage(job, dispatch);

        verify(ledgerRecorder)
                .recordWithoutConversation(
                        job, delivery, ArtifactKinds.PULL_REQUEST, List.of(delivered), "summary-1", null);
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

        verify(ledgerRecorder).record(job, delivery, ArtifactKinds.PULL_REQUEST, List.of(), "summary-1", null);
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
                "Summary",
                List.of(new DiffNote("src/App.java", 10, null, "Inline", "inline-1", null)),
                List.of(),
                null);
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
