package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MentorTurnUsageAccumulatorTest extends BaseUnitTest {

    @Test
    void shouldSwallowAndCountAFailedWriteWhenTheRepositoryFails() {
        MentorTurnRepository failing = mock(MentorTurnRepository.class);
        when(failing.accumulateUsage(any(), anyInt(), any())).thenThrow(new IllegalStateException("connection reset"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MentorTurnUsageAccumulator accumulator = new MentorTurnUsageAccumulator(failing, registry);
        var attempt = new ProxyRouting.BilledAttempt(
                LlmUsageSourceType.MENTOR_TURN, UUID.randomUUID(), 1, BigDecimal.ZERO, "worker-1");

        assertThatCode(() -> accumulator.accumulate(attempt, new ProxyTokenUsage(10, 1, 0, 0, 0)))
                .doesNotThrowAnyException();

        assertThat(registry.counter("llm.proxy.usage.mentor.failure").count()).isEqualTo(1.0);
    }
}
