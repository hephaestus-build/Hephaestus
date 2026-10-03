package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.conversation.ChatSignals;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationCandidateSource;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationThreadCandidate;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewSubject;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.ReviewGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("unit")
@DisplayName("A settled conversation thread's review")
class ConversationReviewSubmitterTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 4L;
    private static final long THREAD_ID = 88L;

    @Mock
    private ConversationCandidateSource candidateSource;

    @Mock
    private AgentJobService agentJobService;

    @Mock
    private SignalRecorder signalRecorder;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private ReviewGate reviewGate;

    private ConversationReviewSubmitter submitter;

    @BeforeEach
    void setUp() {
        submitter = new ConversationReviewSubmitter(
                candidateSource, agentJobService, signalRecorder, transactionTemplate, workspaceRepository, reviewGate);
        lenient()
                .doAnswer(invocation -> {
                    Consumer<TransactionStatus> callback = invocation.getArgument(0);
                    callback.accept(mock(TransactionStatus.class));
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        lenient().when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        lenient()
                .when(reviewGate.evaluateSignal(
                        eq(workspace), any(), eq(TriggerMode.AUTO), any(ReviewSubject.class), anyMap()))
                .thenReturn(new GateDecision.Run(workspace, List.of(), 0, TriggerMode.AUTO));
    }

    @Test
    void threeParticipantsAreThreeJobsAndStillOneSettledOccurrence() {
        givenSubmissionSucceeds();

        long started = submitter.submitAndSettle(candidate(11L, 12L, 13L), key());

        assertThat(started).isEqualTo(3);
        verify(agentJobService, times(3))
                .submitWithOutcome(
                        eq(WORKSPACE_ID),
                        eq(AgentJobType.CONVERSATION_REVIEW),
                        any(),
                        eq(null),
                        any(GateDecision.Run.class));
        verify(signalRecorder).markTriggered(eq(key()), any());
        verify(signalRecorder, never()).markRefused(any(), any());
    }

    @Test
    void anOccurrenceNothingRanForIsRefusedWithTheReasonThatStoppedIt() {
        when(agentJobService.submitWithOutcome(anyLong(), any(), any(), any(), any()))
                .thenReturn(SubmissionOutcome.refused(SignalStateReason.BUDGET_EXHAUSTED));

        long started = submitter.submitAndSettle(candidate(11L), key());

        assertThat(started).isZero();
        verify(signalRecorder).markRefused(key(), SignalStateReason.BUDGET_EXHAUSTED);
    }

    @Test
    void aPartialFanOutStillCountsAsAnOccurrenceThatWasReviewed() {
        AgentJob job = new AgentJob();
        job.setId(UUID.randomUUID());
        when(agentJobService.submitWithOutcome(anyLong(), any(), any(), any(), any()))
                .thenReturn(
                        SubmissionOutcome.refused(SignalStateReason.SUBJECT_UNLINKED), SubmissionOutcome.created(job));

        assertThat(submitter.submitAndSettle(candidate(11L, 12L), key())).isEqualTo(1);
        verify(signalRecorder).markTriggered(key(), job.getId());
        verify(signalRecorder, never()).markRefused(any(), any());
    }

    @Test
    void aThreadWithNoResolvableParticipantIsHeldOpenRatherThanRetired() {
        assertThat(submitter.submitAndSettle(candidate(), key())).isZero();

        verify(signalRecorder).markRefused(key(), SignalStateReason.SUBJECT_UNLINKED);
    }

    @Test
    void aParticipantOutsideReviewCoverageDoesNotStartCompute() {
        when(reviewGate.evaluateSignal(any(), any(), any(), any(), anyMap()))
                .thenReturn(new GateDecision.Skip("outside review coverage", SignalStateReason.OUT_OF_REVIEW_SCOPE));

        assertThat(submitter.submitAndSettle(candidate(11L), key())).isZero();

        verify(agentJobService, never()).submitWithOutcome(anyLong(), any(), any(), any(), any());
        verify(signalRecorder).markRefused(key(), SignalStateReason.OUT_OF_REVIEW_SCOPE);
    }

    @Test
    void aReOfferedSignalWhoseChannelLostConsentIsRetiredRatherThanReviewed() {
        when(candidateSource.candidateById(WORKSPACE_ID, THREAD_ID)).thenReturn(Optional.empty());

        submitter.resubmit(pendingSignal());

        verify(signalRecorder).markRefused(key(), SignalStateReason.ARTIFACT_GONE);
        verify(agentJobService, never()).submitWithOutcome(anyLong(), any(), any(), any(), any());
    }

    @Test
    void aReOfferedSignalOnAStillConsentedThreadIsReviewedAgain() {
        when(candidateSource.candidateById(WORKSPACE_ID, THREAD_ID)).thenReturn(Optional.of(candidate(11L)));
        givenSubmissionSucceeds();

        submitter.resubmit(pendingSignal());

        verify(agentJobService)
                .submitWithOutcome(eq(WORKSPACE_ID), any(), any(), eq(null), any(GateDecision.Run.class));
        verify(signalRecorder).markTriggered(eq(key()), any());
    }

    @Test
    void theKindItSpeaksForIsTheChatThread() {
        assertThat(submitter.artifactKind()).isEqualTo(ChatSignals.CONVERSATION_THREAD);
    }

    private void givenSubmissionSucceeds() {
        when(agentJobService.submitWithOutcome(anyLong(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    AgentJob job = new AgentJob();
                    job.setId(UUID.randomUUID());
                    return SubmissionOutcome.created(job);
                });
    }

    private static SignalKey key() {
        return ChatSignals.threadSettledKey(WORKSPACE_ID, THREAD_ID, "1700000000.000100", "1700000600.000200", 6);
    }

    private static ConversationThreadCandidate candidate(long... participants) {
        return new ConversationThreadCandidate(
                WORKSPACE_ID,
                THREAD_ID,
                "C123",
                "#design",
                "1700000000.000100",
                "1700000600.000200",
                null,
                Arrays.stream(participants).boxed().toList());
    }

    private static ArtifactSignal pendingSignal() {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        SignalKey key = key();
        ArtifactSignal signal = new ArtifactSignal();
        signal.setId(UUID.randomUUID());
        signal.setWorkspace(workspace);
        signal.setArtifactKind(ChatSignals.CONVERSATION_THREAD.value());
        signal.setArtifactId(THREAD_ID);
        signal.setSignalName(ChatSignals.CONVERSATION_THREAD_SETTLED.value());
        signal.setRevision(key.revision().value());
        signal.setState(SignalState.PENDING);
        return signal;
    }
}
