package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * {@link MentorTurnMeter} may lag the row but must never claim spend the durable record does not have:
 * advancing it inline, inside the still-open {@code REQUIRES_NEW} transaction, would leave a failed
 * commit holding tokens the row never got.
 */
class MentorTurnMeterCommitOrderingTest extends BaseUnitTest {

    private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
    private final MentorProxyCredentialRegistry credentialRegistry = mock(MentorProxyCredentialRegistry.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final MentorTurnUsageAccumulator accumulator =
            new MentorTurnUsageAccumulator(chatMessageRepository, credentialRegistry, meterRegistry);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("the meter is not advanced until the row write commits")
    void shouldMirrorOntoTheMeterOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        UUID turnId = UUID.randomUUID();
        ProxyTokenUsage usage = usageOf(turnId);
        when(chatMessageRepository.accumulateLlmUsage(any(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(1);

        accumulator.accumulate(attempt(turnId), usage);

        verify(credentialRegistry, never()).accumulate(any(), any());

        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(credentialRegistry).accumulate(turnId, usage);
    }

    @Test
    @DisplayName("a fenced-out write never reaches the meter, committed or not")
    void shouldNotMirrorASupersededWrite() {
        TransactionSynchronizationManager.initSynchronization();
        UUID turnId = UUID.randomUUID();
        when(chatMessageRepository.accumulateLlmUsage(any(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(0);

        accumulator.accumulate(attempt(turnId), usageOf(turnId));
        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(credentialRegistry, never()).accumulate(any(), any());
        assertThat(meterRegistry.counter("llm.proxy.usage.mentor.superseded").count())
                .isEqualTo(1.0);
    }

    private static ProxyRouting.BilledAttempt attempt(UUID turnId) {
        return new ProxyRouting.BilledAttempt(LlmUsageSourceType.MENTOR_TURN, turnId, 1, BigDecimal.ZERO, null);
    }

    private static ProxyTokenUsage usageOf(UUID turnId) {
        return new ProxyTokenUsage(100, 50, 10, 5, 0);
    }
}
