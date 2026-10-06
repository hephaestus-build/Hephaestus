package de.tum.cit.aet.hephaestus.agent.handler;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatch;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchDestination;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import tools.jackson.databind.json.JsonMapper;

class PracticeFeedbackDispatchRecoveryTest extends BaseUnitTest {

    private static final UUID FEEDBACK_ID = UUID.fromString("0b7d3c2e-8f14-4a6b-9c5d-2e1f0a3b4c5d");
    private static final InlineFeedbackChannel.DeliveredSignal LANDED =
            lineSignal(12, InlineFeedbackChannel.Disposition.POSTED);
    private static final InlineFeedbackChannel.DeliveredSignal NOT_LANDED =
            lineSignal(20, InlineFeedbackChannel.Disposition.FAILED);

    @Mock
    private FeedbackDispatchRepository dispatches;

    @Mock
    private AgentJobRepository jobs;

    @Mock
    private FeedbackRepository feedback;

    @Mock
    private PracticeFeedbackDispatchService service;

    @Mock
    private FeedbackLedgerRecorder ledgerRecorder;

    @Mock
    private FeedbackDeliveryService feedbackDeliveryService;

    private PracticeFeedbackDispatchRecovery recovery;

    @BeforeEach
    void setUp() {
        recovery = new PracticeFeedbackDispatchRecovery(
                dispatches, jobs, feedback, service, ledgerRecorder, feedbackDeliveryService);
        lenient().when(service.projectRecovered(any(), any())).thenAnswer(invocation -> {
            Runnable projection = invocation.getArgument(1);
            projection.run();
            return true;
        });
    }

    @Test
    void exhaustedApprovedDispatchTerminalizesBothDispatchAndFeedback() {
        UUID dispatchId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        FeedbackDispatch dispatch = dispatch(
                dispatchId,
                jobId,
                FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE,
                FEEDBACK_ID,
                FeedbackDispatchState.UNCERTAIN,
                null);
        FeedbackDispatch failed = dispatch(
                dispatchId,
                jobId,
                FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE,
                FEEDBACK_ID,
                FeedbackDispatchState.FAILED,
                null);
        when(dispatches.findExhausted(any(), anyInt(), any())).thenReturn(List.of(dispatch));
        when(dispatches.findByIdAndWorkspaceId(dispatchId, 7L)).thenReturn(Optional.of(failed));
        var unit = mock(Feedback.class);
        when(feedback.findByIdAndWorkspaceId(FEEDBACK_ID, 7L)).thenReturn(Optional.of(unit));

        recovery.recover();

        verify(service).fail(dispatch, "Dispatch retry limit exhausted");
        verify(feedback).markApprovedFailed(7L, FEEDBACK_ID);
    }

    @Test
    void shouldFailAnApprovedProposalWhoseJobIsGoneOnlyPartlyWhenALineNoteAlreadyLanded() {
        UUID dispatchId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        FeedbackDispatch candidate = lineNotesDispatch(dispatchId, jobId, FeedbackDispatchState.UNCERTAIN, null);
        FeedbackDispatch failed = lineNotesDispatch(dispatchId, jobId, FeedbackDispatchState.FAILED, null);
        var unit = mock(Feedback.class);
        when(dispatches.findRecoverable(any(), anyInt(), any())).thenReturn(List.of(candidate));
        when(dispatches.findByIdAndWorkspaceId(dispatchId, 7L))
                .thenReturn(Optional.of(candidate))
                .thenReturn(Optional.of(failed));
        when(feedback.findByIdAndWorkspaceId(FEEDBACK_ID, 7L)).thenReturn(Optional.of(unit));
        when(service.deliveredSignals(failed)).thenReturn(List.of(LANDED, NOT_LANDED));

        recovery.recover();

        verify(service).fail(candidate, "Dispatch job no longer exists");
        verify(ledgerRecorder).recordApprovedPlacements(unit, null, null, List.of(LANDED, NOT_LANDED));
        verify(feedback).findByIdAndWorkspaceId(FEEDBACK_ID, 7L);
        verify(feedback).markApprovedPartiallyFailed(7L, FEEDBACK_ID);
        verifyNoMoreInteractions(feedback);
    }

    @Test
    void shouldFailAChangedApprovedProposalWithoutDispatchingIt() {
        UUID dispatchId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        FeedbackDispatch candidate = lineNotesDispatch(dispatchId, jobId, FeedbackDispatchState.UNCERTAIN, null);
        FeedbackDispatch failed = lineNotesDispatch(dispatchId, jobId, FeedbackDispatchState.FAILED, null);
        AgentJob job = job();
        var unit = mock(Feedback.class);
        when(dispatches.findRecoverable(any(), anyInt(), any())).thenReturn(List.of(candidate));
        when(dispatches.findByIdAndWorkspaceId(dispatchId, 7L))
                .thenReturn(Optional.of(candidate))
                .thenReturn(Optional.of(failed));
        when(jobs.findByIdAndWorkspaceId(jobId, 7L)).thenReturn(Optional.of(job));
        when(feedback.findByIdAndWorkspaceId(FEEDBACK_ID, 7L)).thenReturn(Optional.of(unit));
        when(service.matchesImmutablePackage(unit, candidate)).thenReturn(false);

        recovery.recover();

        verify(service, never()).recover(any(), any());
        verify(service).fail(candidate, "Approved feedback is missing or no longer matches its immutable package");
        verify(feedback).markApprovedFailed(7L, FEEDBACK_ID);
    }

    @ParameterizedTest(name = "{0} with a {1} line note")
    @MethodSource("terminalLineNoteOutcomes")
    void shouldReconcileATerminalApprovalFromItsRetainedLineNotes(
            FeedbackDispatchState state,
            InlineFeedbackChannel.Disposition disposition,
            Consumer<FeedbackRepository> transition) {
        UUID dispatchId = UUID.randomUUID();
        FeedbackDispatch terminal = lineNotesDispatch(
                dispatchId,
                UUID.randomUUID(),
                state,
                state == FeedbackDispatchState.SUPPRESSED ? FeedbackSuppressionReason.APPROVAL_STALE.name() : null);
        var retained = List.of(lineSignal(12, disposition));
        var unit = mock(Feedback.class);
        when(dispatches.findUnprojectedTerminal(any(), any())).thenReturn(List.of(terminal));
        when(jobs.findByIdAndWorkspaceId(terminal.getAgentJobId(), 7L)).thenReturn(Optional.of(job()));
        when(feedback.findByIdAndWorkspaceId(FEEDBACK_ID, 7L)).thenReturn(Optional.of(unit));
        when(service.deliveredSignals(terminal)).thenReturn(retained);

        recovery.recover();

        verify(ledgerRecorder).recordApprovedPlacements(unit, null, null, retained);
        verify(feedback).findByIdAndWorkspaceId(FEEDBACK_ID, 7L);
        transition.accept(verify(feedback));
        verifyNoMoreInteractions(feedback);
    }

    static Stream<Arguments> terminalLineNoteOutcomes() {
        String stale = FeedbackSuppressionReason.APPROVAL_STALE.name();
        return Stream.of(
                arguments(FeedbackDispatchState.SUPPRESSED, InlineFeedbackChannel.Disposition.POSTED, (Consumer<
                                FeedbackRepository>)
                        repository -> repository.markApprovedPartiallyDelivered(7L, FEEDBACK_ID, stale)),
                arguments(FeedbackDispatchState.SUPPRESSED, InlineFeedbackChannel.Disposition.FAILED, (Consumer<
                                FeedbackRepository>)
                        repository -> repository.markApprovedSuppressed(7L, FEEDBACK_ID, stale)),
                arguments(FeedbackDispatchState.FAILED, InlineFeedbackChannel.Disposition.FELL_BACK, (Consumer<
                                FeedbackRepository>)
                        repository -> repository.markApprovedPartiallyFailed(7L, FEEDBACK_ID)),
                arguments(FeedbackDispatchState.FAILED, InlineFeedbackChannel.Disposition.FAILED, (Consumer<
                                FeedbackRepository>)
                        repository -> repository.markApprovedFailed(7L, FEEDBACK_ID)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recoveredResultsWithALandedLineNote")
    void shouldKeepALandedLineNoteAsPartialWhenARecoveredAttemptDoesNotFinish(
            PracticeFeedbackDispatchService.Result result, Consumer<FeedbackRepository> partialTransition) {
        FeedbackDispatch dispatch =
                lineNotesDispatch(UUID.randomUUID(), UUID.randomUUID(), FeedbackDispatchState.UNCERTAIN, null);
        AgentJob job = job();
        var unit = mock(Feedback.class);
        when(dispatches.findRecoverable(any(), anyInt(), any())).thenReturn(List.of(dispatch));
        when(dispatches.findByIdAndWorkspaceId(dispatch.getId(), 7L)).thenReturn(Optional.of(dispatch));
        when(jobs.findByIdAndWorkspaceId(dispatch.getAgentJobId(), 7L)).thenReturn(Optional.of(job));
        when(feedback.findByIdAndWorkspaceId(FEEDBACK_ID, 7L)).thenReturn(Optional.of(unit));
        when(service.matchesImmutablePackage(unit, dispatch)).thenReturn(true);
        when(service.recover(dispatch, job)).thenReturn(result);

        recovery.recover();

        verify(ledgerRecorder).recordApprovedPlacements(unit, null, null, result.deliveredSignals());
        verify(feedback, times(2)).findByIdAndWorkspaceId(FEEDBACK_ID, 7L);
        partialTransition.accept(verify(feedback));
        verifyNoMoreInteractions(feedback);
    }

    static Stream<Arguments> recoveredResultsWithALandedLineNote() {
        return Stream.of(
                arguments(
                        PracticeFeedbackDispatchService.Result.uncertain(null, null, List.of(LANDED, NOT_LANDED)),
                        (Consumer<FeedbackRepository>)
                                repository -> repository.markApprovedPartiallyDelivered(7L, FEEDBACK_ID, null)),
                arguments(
                        PracticeFeedbackDispatchService.Result.failed(null, null, List.of(LANDED, NOT_LANDED)),
                        (Consumer<FeedbackRepository>)
                                repository -> repository.markApprovedPartiallyFailed(7L, FEEDBACK_ID)));
    }

    @Test
    void recoveredApprovedDeliveryReconcilesTheFeedbackLifecycle() {
        UUID feedbackId = UUID.randomUUID();
        FeedbackDispatch dispatch = dispatch(FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE, feedbackId);
        AgentJob job = job();
        var unit = mock(Feedback.class);
        when(service.matchesImmutablePackage(unit, dispatch)).thenReturn(true);
        when(dispatches.findRecoverable(any(), anyInt(), any())).thenReturn(List.of(dispatch));
        when(dispatches.findByIdAndWorkspaceId(dispatch.getId(), 7L)).thenReturn(Optional.of(dispatch));
        when(jobs.findByIdAndWorkspaceId(dispatch.getAgentJobId(), 7L)).thenReturn(Optional.of(job));
        when(feedback.findByIdAndWorkspaceId(feedbackId, 7L)).thenReturn(Optional.of(unit));
        when(service.recover(dispatch, job)).thenReturn(PracticeFeedbackDispatchService.Result.sent("provider-42"));

        recovery.recover();

        verify(feedback).markApprovedDelivered(7L, feedbackId);
        verify(service).projectRecovered(any(), any());
    }

    @Test
    void anUnprojectedAutomaticPackageUsesThePackageProjector() {
        FeedbackDispatch dispatch = dispatch(
                FeedbackDispatchDestination.AUTOMATIC_REVIEW_PACKAGE, null, FeedbackDispatchState.SENT, "provider-42");
        AgentJob job = job();
        when(dispatches.findUnprojectedTerminal(any(), any())).thenReturn(List.of(dispatch));
        when(jobs.findByIdAndWorkspaceId(dispatch.getAgentJobId(), 7L)).thenReturn(Optional.of(job));

        recovery.recover();

        verify(feedbackDeliveryService).projectAutomaticPackage(job, dispatch);
        verify(service, never()).projectRecovered(any(), any());
    }

    @Test
    void aRecoveredAutomaticPackageUsesThePersistedTerminalPackage() {
        UUID dispatchId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        FeedbackDispatch candidate = dispatch(
                dispatchId,
                jobId,
                FeedbackDispatchDestination.AUTOMATIC_REVIEW_PACKAGE,
                null,
                FeedbackDispatchState.UNCERTAIN,
                null);
        FeedbackDispatch terminal = dispatch(
                dispatchId,
                jobId,
                FeedbackDispatchDestination.AUTOMATIC_REVIEW_PACKAGE,
                null,
                FeedbackDispatchState.SENT,
                "provider-42");
        AgentJob job = job();
        job.setId(jobId);
        when(dispatches.findRecoverable(any(), anyInt(), any())).thenReturn(List.of(candidate));
        when(dispatches.findByIdAndWorkspaceId(candidate.getId(), 7L)).thenReturn(Optional.of(candidate));
        when(jobs.findByIdAndWorkspaceId(candidate.getAgentJobId(), 7L)).thenReturn(Optional.of(job));
        when(service.recover(candidate, job)).thenReturn(PracticeFeedbackDispatchService.Result.sent("provider-42"));
        when(service.automaticPackage(job)).thenReturn(terminal);

        recovery.recover();

        verify(feedbackDeliveryService).recordAutomaticPackage(job, terminal);
        verify(service, never()).projectRecovered(any(), any());
    }

    @Test
    void anUnprojectedTerminalApprovalIsReconciledAfterRestart() {
        UUID feedbackId = UUID.randomUUID();
        FeedbackDispatch dispatch = dispatch(
                FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE,
                feedbackId,
                FeedbackDispatchState.SENT,
                "provider-42");
        AgentJob job = job();
        var unit = mock(Feedback.class);
        when(dispatches.findUnprojectedTerminal(any(), any())).thenReturn(List.of(dispatch));
        when(jobs.findByIdAndWorkspaceId(dispatch.getAgentJobId(), 7L)).thenReturn(Optional.of(job));
        when(feedback.findByIdAndWorkspaceId(feedbackId, 7L)).thenReturn(Optional.of(unit));

        recovery.recover();

        verify(feedback).markApprovedDelivered(7L, feedbackId);
        verify(service).projectRecovered(any(), any());
    }

    private static AgentJob job() {
        AgentJob job = new AgentJob();
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        return job;
    }

    private static FeedbackDispatch dispatch(FeedbackDispatchDestination destination, @Nullable UUID feedbackId) {
        return dispatch(destination, feedbackId, FeedbackDispatchState.UNCERTAIN, null);
    }

    private static FeedbackDispatch dispatch(
            FeedbackDispatchDestination destination,
            @Nullable UUID feedbackId,
            FeedbackDispatchState state,
            @Nullable String externalRef) {
        return dispatch(UUID.randomUUID(), UUID.randomUUID(), destination, feedbackId, state, externalRef);
    }

    private static FeedbackDispatch dispatch(
            UUID id,
            UUID jobId,
            FeedbackDispatchDestination destination,
            @Nullable UUID feedbackId,
            FeedbackDispatchState state,
            @Nullable String externalRef) {
        return dispatch(
                id,
                jobId,
                destination,
                feedbackId,
                state,
                externalRef,
                new ReviewResultParser.DeliveryContent("body", List.of(), List.of(), null),
                false,
                null);
    }

    /** An approved package of line notes alone, after its line-note write began. */
    private static FeedbackDispatch lineNotesDispatch(
            UUID id, UUID jobId, FeedbackDispatchState state, @Nullable String suppressionReason) {
        return dispatch(
                id,
                jobId,
                FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE,
                FEEDBACK_ID,
                state,
                null,
                new ReviewResultParser.DeliveryContent(
                        null,
                        List.of(
                                new ReviewResultParser.DiffNote(
                                        "src/Review.java", 12, null, "Name the failure case", "review-key", null),
                                new ReviewResultParser.DiffNote(
                                        "src/Review.java", 20, null, "Cover the empty input", "empty-key", null)),
                        List.of(),
                        null),
                true,
                suppressionReason);
    }

    private static FeedbackDispatch dispatch(
            UUID id,
            UUID jobId,
            FeedbackDispatchDestination destination,
            @Nullable UUID feedbackId,
            FeedbackDispatchState state,
            @Nullable String externalRef,
            ReviewResultParser.DeliveryContent content,
            boolean inlineWriteStarted,
            @Nullable String suppressionReason) {
        var mapper = JsonMapper.builder().build();
        String summary = content.mrNote();
        return new FeedbackDispatch(
                id,
                "dispatch:" + id,
                7L,
                jobId,
                feedbackId,
                destination,
                state,
                summary == null ? "" : summary,
                mapper.valueToTree(List.of("practice")),
                mapper.valueToTree(content),
                mapper.valueToTree(List.of()),
                false,
                null,
                inlineWriteStarted,
                externalRef,
                null,
                null,
                null,
                Instant.now(),
                1,
                suppressionReason,
                null,
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    private static InlineFeedbackChannel.DeliveredSignal lineSignal(
            int line, InlineFeedbackChannel.Disposition disposition) {
        return new InlineFeedbackChannel.DeliveredSignal(
                "approved:" + FEEDBACK_ID + ":" + line,
                FeedbackAnchor.DiffAnchor.singleLine("src/Review.java", line),
                disposition,
                disposition == InlineFeedbackChannel.Disposition.FAILED ? null : "note-" + line,
                null);
    }
}
