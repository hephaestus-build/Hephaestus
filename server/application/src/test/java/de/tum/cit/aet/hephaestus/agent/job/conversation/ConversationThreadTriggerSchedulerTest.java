package de.tum.cit.aet.hephaestus.agent.job.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.conversation.ChatSignals;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationCandidateSource;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationThreadCandidate;
import de.tum.cit.aet.hephaestus.agent.job.ConversationReviewSubmitter;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** The conversation-review trigger gates: quiescence, depth, growth, and ts parsing. */
class ConversationThreadTriggerSchedulerTest extends BaseUnitTest {

    private static final int QUIESCENCE_MIN = 10;
    private static final int MIN_TURNS = 4;
    private static final int MIN_GROWTH = 2;
    private static final long WORKSPACE_ID = 9L;
    private static final long THREAD_ID = 77L;
    private static final String THREAD_TS = "1700000000.000100";

    /**
     * A scheduler over one thread that passes every gate, with the transaction template made transparent
     * so the ledger call the sweep makes inside it is the one the test sees.
     */
    private static final class Fixture {

        private final ConversationCandidateSource candidateSource = mock(ConversationCandidateSource.class);
        private final ConversationReviewSubmitter submitter = mock(ConversationReviewSubmitter.class);
        private final SignalRecorder signalRecorder = mock(SignalRecorder.class);
        private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        private String lastTs = "";

        @SuppressWarnings("unchecked")
        Fixture() {
            when(transactionTemplate.execute(any()))
                    .thenAnswer(invocation -> ((TransactionCallback<Object>) invocation.getArgument(0))
                            .doInTransaction(mock(TransactionStatus.class)));
        }

        void givenOneReadyThread() {
            Instant now = Instant.now();
            lastTs = (now.getEpochSecond() - Duration.ofMinutes(20).toSeconds()) + ".000200";
            when(candidateSource.settledCandidates(anyInt()))
                    .thenReturn(List.of(new ConversationThreadCandidate(
                            WORKSPACE_ID, THREAD_ID, "C1", "#design", THREAD_TS, lastTs, null, new long[] {5L})));
            when(candidateSource.liveTurnCount(anyLong(), any(), any())).thenReturn(8L);
            when(candidateSource.liveTurnCountSince(anyLong(), any(), any(), any()))
                    .thenReturn(4L);
            lenient().when(submitter.submitAndSettle(any(), any())).thenReturn(1L);
        }

        ConversationThreadTriggerScheduler scheduler() {
            return new ConversationThreadTriggerScheduler(
                    candidateSource, submitter, signalRecorder, transactionTemplate, true);
        }
    }

    /** A Slack ts (seconds.micro) whose second part is {@code now - ageSeconds}. */
    private static String tsAgedBy(Instant now, long ageSeconds) {
        return (now.getEpochSecond() - ageSeconds) + ".123456";
    }

    @ParameterizedTest
    @CsvSource({
        // ageMin, turns, growthSinceWatermark, expectedPass
        "2, 8, 5, false", // still inside the 10-minute quiescence window
        "15, 8, 5, true", // settled + deep + grown
        "15, 3, 3, false", // too few turns even when settled and grown
        "15, 8, 1, false", // no growth past the watermark (late-reply / re-sweep) does not re-fire
        "15, 8, 2, true", // exactly enough growth
    })
    void passesGatesEvaluatesQuiescenceDepthAndGrowth(int ageMin, int turns, int growth, boolean expected) {
        Instant now = Instant.now();
        String lastTs = tsAgedBy(now, Duration.ofMinutes(ageMin).toSeconds());
        assertThat(ConversationThreadTriggerScheduler.passesGates(
                        now, lastTs, turns, growth, QUIESCENCE_MIN, MIN_TURNS, MIN_GROWTH))
                .isEqualTo(expected);
    }

    @Test
    void unparseableOrNullLastTsIsRejected() {
        Instant now = Instant.now();
        assertThat(ConversationThreadTriggerScheduler.passesGates(
                        now, null, 8, 5, QUIESCENCE_MIN, MIN_TURNS, MIN_GROWTH))
                .isFalse();
        assertThat(ConversationThreadTriggerScheduler.passesGates(
                        now, "not-a-ts", 8, 5, QUIESCENCE_MIN, MIN_TURNS, MIN_GROWTH))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1700000000.123456", "1700000000"})
    void slackTsEpochSecondsParsesSecondsPart(String ts) {
        assertThat(ConversationThreadTriggerScheduler.slackTsEpochSeconds(ts)).isEqualTo(1700000000L);
    }

    @Test
    void slackTsEpochSecondsReturnsNullForNullBlankOrGarbage() {
        assertThat(ConversationThreadTriggerScheduler.slackTsEpochSeconds(null)).isNull();
        assertThat(ConversationThreadTriggerScheduler.slackTsEpochSeconds("  ")).isNull();
        assertThat(ConversationThreadTriggerScheduler.slackTsEpochSeconds("abc.def"))
                .isNull();
    }

    /**
     * The occurrence reaches the ledger before anything is submitted, and the ledger's uniqueness — not a
     * flag in memory — decides who acts on it, so a passed-over conversation still leaves a row saying why.
     */
    @Test
    void aThreadThatPassesTheGatesIsRecordedBeforeAnythingIsSubmitted() {
        Fixture f = new Fixture();
        f.givenOneReadyThread();
        when(f.signalRecorder.record(any(), any(), eq(DiscoveredVia.SYNC))).thenReturn(true);

        f.scheduler().sweepNow();

        InOrder inOrder = inOrder(f.signalRecorder, f.submitter);
        inOrder.verify(f.signalRecorder).record(any(), any(), eq(DiscoveredVia.SYNC));
        inOrder.verify(f.submitter).submitAndSettle(any(), any());
    }

    /** The identity is the thread as it stands: where it starts, where it ends, and how much was said. */
    @Test
    void theRecordedOccurrenceIsKeyedOnTheThreadAsItStands() {
        Fixture f = new Fixture();
        f.givenOneReadyThread();
        when(f.signalRecorder.record(any(), any(), eq(DiscoveredVia.SYNC))).thenReturn(true);

        f.scheduler().sweepNow();

        ArgumentCaptor<SignalKey> captor = ArgumentCaptor.forClass(SignalKey.class);
        verify(f.signalRecorder).record(captor.capture(), any(), eq(DiscoveredVia.SYNC));
        assertThat(captor.getValue())
                .isEqualTo(ChatSignals.threadSettledKey(WORKSPACE_ID, THREAD_ID, THREAD_TS, f.lastTs, 8));
    }

    /**
     * A second sweep that agrees the same thread is ready loses the ledger's insert and does nothing. The
     * gates run on counts read a moment earlier, so two overlapping sweeps genuinely can both arrive here.
     */
    @Test
    void aSweepThatLosesTheLedgerInsertSubmitsNothing() {
        Fixture f = new Fixture();
        f.givenOneReadyThread();
        // The submit stub the fixture sets up goes unused here, and that is the assertion: nothing is
        // submitted once the ledger says another sweep owns this occurrence.
        lenient().when(f.signalRecorder.record(any(), any(), any())).thenReturn(false);

        assertThat(f.scheduler().sweepNow()).isZero();

        verifyNoInteractions(f.submitter);
        verify(f.candidateSource, never()).markReviewed(anyLong(), anyLong(), any());
    }

    /** The watermark still moves only on a review that actually started, as it did before. */
    @Test
    void theGrowthWatermarkMovesOnlyWhenAReviewStarted() {
        Fixture f = new Fixture();
        f.givenOneReadyThread();
        when(f.signalRecorder.record(any(), any(), any())).thenReturn(true);
        when(f.submitter.submitAndSettle(any(), any())).thenReturn(0L);

        f.scheduler().sweepNow();

        verify(f.candidateSource, never()).markReviewed(anyLong(), anyLong(), any());
    }

    @Test
    void shouldDoNoWorkWhenConversationIngestIsOff() {
        ConversationCandidateSource candidateSource = mock(ConversationCandidateSource.class);
        ConversationReviewSubmitter submitter = mock(ConversationReviewSubmitter.class);
        SignalRecorder signalRecorder = mock(SignalRecorder.class);
        var disabled = new ConversationThreadTriggerScheduler(
                candidateSource, submitter, signalRecorder, mock(TransactionTemplate.class), false);

        // A dormant subsystem leaves no ledger row claiming it looked.
        assertThat(disabled.sweepNow()).isZero();
        verifyNoInteractions(candidateSource, submitter, signalRecorder);
    }

    @Test
    void shouldScanForCandidatesWhenConversationIngestIsOn() {
        ConversationCandidateSource candidateSource = mock(ConversationCandidateSource.class);
        ConversationReviewSubmitter submitter = mock(ConversationReviewSubmitter.class);
        SignalRecorder signalRecorder = mock(SignalRecorder.class);
        when(candidateSource.settledCandidates(anyInt())).thenReturn(List.of());
        var enabled = new ConversationThreadTriggerScheduler(
                candidateSource, submitter, signalRecorder, mock(TransactionTemplate.class), true);

        assertThat(enabled.sweepNow()).isZero();
        verify(candidateSource).settledCandidates(anyInt());
        verifyNoInteractions(submitter, signalRecorder);
    }
}
