package de.tum.cit.aet.hephaestus.agent.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatch;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchDestination;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.json.JsonMapper;

class PracticeFeedbackDispatchRecoveryTest extends BaseUnitTest {

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
        FeedbackDispatch dispatch = dispatch(FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE, UUID.randomUUID());
        when(dispatches.findExhausted(any(), anyInt(), any())).thenReturn(List.of(dispatch));

        recovery.recover();

        verify(service).fail(dispatch, "Dispatch retry limit exhausted");
        verify(feedback).markApprovedFailed(dispatch.getWorkspaceId(), dispatch.approvedFeedbackId());
    }

    @Test
    void recoveredApprovedDeliveryReconcilesTheFeedbackLifecycle() {
        UUID feedbackId = UUID.randomUUID();
        FeedbackDispatch dispatch = dispatch(FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE, feedbackId);
        AgentJob job = job();
        var unit = mock(de.tum.cit.aet.hephaestus.practices.feedback.Feedback.class);
        when(unit.getBody()).thenReturn(dispatch.getBody());
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

        verify(feedbackDeliveryService).projectAutomaticPackage(job, terminal);
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
        var unit = mock(de.tum.cit.aet.hephaestus.practices.feedback.Feedback.class);
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
        var mapper = JsonMapper.builder().build();
        return new FeedbackDispatch(
                id,
                "dispatch:" + id,
                7L,
                jobId,
                feedbackId,
                destination,
                state,
                "body",
                mapper.valueToTree(List.of("practice")),
                mapper.valueToTree(
                        new PracticeDetectionResultParser.DeliveryContent("body", List.of(), List.of(), null)),
                mapper.valueToTree(List.of()),
                false,
                externalRef,
                null,
                null,
                Instant.now(),
                1,
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
    }
}
