package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.conversation.ConversationalDeliveryReconciler;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.TranslatorState;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.UIMessageChunk;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.agent.usage.PricingState;
import de.tum.cit.aet.hephaestus.mentor.ChatMessage;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import de.tum.cit.aet.hephaestus.mentor.ChatThreadRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnLlmUsage;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A turn that crashes, times out, or is killed mid-flight has still made real provider calls that were
 * paid for, and its end-of-turn report is exactly what it never got to produce — so the only account of
 * those calls is the running total the LLM proxy wrote to the turn's row as it served each one.
 */
class MentorTurnCrashBillingTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 7L;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final LlmPriceSnapshot PRICE = new LlmPriceSnapshot(
            FundingSource.INSTANCE,
            PricingState.PRICED,
            1L,
            null,
            new BigDecimal("10"),
            new BigDecimal("20"),
            new BigDecimal("1"),
            new BigDecimal("1"));

    private ChatMessageRepository chatMessageRepository;
    private LlmUsageRecorder usageRecorder;
    private MentorTurnPersistence persistence;

    private final UUID assistantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        chatMessageRepository = mock(ChatMessageRepository.class);
        usageRecorder = mock(LlmUsageRecorder.class);
        persistence = new MentorTurnPersistence(
                mock(ChatThreadRepository.class),
                chatMessageRepository,
                mock(WorkspaceRepository.class),
                mock(ConversationalDeliveryReconciler.class),
                usageRecorder,
                noOpTransactionManager());

        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        ChatThread thread = new ChatThread();
        thread.setId(UUID.randomUUID());
        thread.setWorkspace(workspace);
        ChatMessage assistant = new ChatMessage();
        assistant.setId(assistantId);
        assistant.setThread(thread);
        when(chatMessageRepository.findById(assistantId)).thenReturn(Optional.of(assistant));
        when(chatMessageRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static PlatformTransactionManager noOpTransactionManager() {
        return mock(PlatformTransactionManager.class);
    }

    private MentorTurnPersistence.TurnPersistenceCookie cookie() {
        return new MentorTurnPersistence.TurnPersistenceCookie(
                UUID.randomUUID(), UUID.randomUUID(), assistantId, Instant.now(), "gpt-x", PRICE);
    }

    private void proxyRecorded(MentorTurnLlmUsage usage) {
        when(chatMessageRepository.findLlmUsageById(assistantId)).thenReturn(Optional.of(usage));
    }

    private static TranslatorState startedTurnWithNoRunnerReport() {
        TranslatorState state = new TranslatorState(UUID.randomUUID());
        state.bindAdmission("gpt-x", PRICE);
        state.markLlmCallStarted();
        return state;
    }

    @Test
    @DisplayName("a turn that dies before the runner reports anything is billed for the calls the proxy saw")
    void aCrashedTurnIsBilledForWhatTheProxyObserved() {
        proxyRecorded(new MentorTurnLlmUsage(3, 6_000, 900, 50, 100, 0));

        persistence.interrupt(cookie(), startedTurnWithNoRunnerReport(), new IllegalStateException("runner died"));

        ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
        verify(usageRecorder).record(eq(WORKSPACE_ID), sample.capture());
        verify(usageRecorder, never()).recordUnverifiable(any(), any());
        assertThat(sample.getValue().sourceType()).isEqualTo(LlmUsageSourceType.MENTOR_TURN);
        assertThat(sample.getValue().sourceId()).isEqualTo(assistantId);
        assertThat(sample.getValue().inputTokens()).isEqualTo(6_000);
        assertThat(sample.getValue().outputTokens()).isEqualTo(900);
        assertThat(sample.getValue().cacheReadTokens()).isEqualTo(100);
        assertThat(sample.getValue().reasoningTokens()).isEqualTo(50);
        assertThat(sample.getValue().totalCalls()).isEqualTo(3);
    }

    @Test
    @DisplayName("a turn that reported its own usage is billed from that report, not from the proxy meter")
    void theRunnerReportWinsWhenThereIsOne() {
        TranslatorState state = startedTurnWithNoRunnerReport();
        ObjectNode reported = NODES.objectNode();
        reported.put("input", 11).put("output", 22);
        state.observeUsage(reported);

        persistence.complete(cookie(), state, new UIMessageChunk.Finish(UIMessageChunk.FinishReason.STOP, null));

        ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
        verify(usageRecorder).record(eq(WORKSPACE_ID), sample.capture());
        assertThat(sample.getValue().inputTokens()).isEqualTo(11);
        assertThat(sample.getValue().outputTokens()).isEqualTo(22);
        // Not even consulted: summing the two views of the same calls would double-bill them.
        verify(chatMessageRepository, never()).findLlmUsageById(any());
    }

    static Stream<Arguments> nothingToBillFrom() {
        return Stream.of(
                Arguments.of(
                        "a row of zeroes — the turn died before its first call returned",
                        Optional.of(MentorTurnLlmUsage.NONE)),
                Arguments.of(
                        "no row at all — the thread was deleted under us mid-turn",
                        Optional.<MentorTurnLlmUsage>empty()));
    }

    /**
     * The one-call, zero-token floor is deliberate: it stops an unobserved turn from looking like a turn
     * that never happened, since the ledger row is the operator's only trace of it. The pricing-state
     * downgrade is the recorder's job, not this one's.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nothingToBillFrom")
    @DisplayName("a turn nobody observed is booked as a one-call, zero-token unverifiable event")
    void aTurnWithNoObservationsAtAllStaysUnverifiable(String observation, Optional<MentorTurnLlmUsage> row) {
        when(chatMessageRepository.findLlmUsageById(assistantId)).thenReturn(row);

        persistence.interrupt(cookie(), startedTurnWithNoRunnerReport(), new IllegalStateException("runner died"));

        ArgumentCaptor<LlmUsageRecorder.LlmUsageSample> sample =
                ArgumentCaptor.forClass(LlmUsageRecorder.LlmUsageSample.class);
        verify(usageRecorder).recordUnverifiable(eq(WORKSPACE_ID), sample.capture());
        verify(usageRecorder, never()).record(any(), any());
        assertThat(sample.getValue().sourceType()).isEqualTo(LlmUsageSourceType.MENTOR_TURN);
        assertThat(sample.getValue().sourceId()).isEqualTo(assistantId);
        assertThat(sample.getValue().model()).isEqualTo("gpt-x");
        assertThat(sample.getValue().price()).isEqualTo(PRICE);
        assertThat(sample.getValue().inputTokens()).isZero();
        assertThat(sample.getValue().outputTokens()).isZero();
        assertThat(sample.getValue().cacheReadTokens()).isZero();
        assertThat(sample.getValue().cacheWriteTokens()).isZero();
        assertThat(sample.getValue().reasoningTokens()).isZero();
        assertThat(sample.getValue().totalCalls()).isEqualTo(1);
    }
}
