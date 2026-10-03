package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class AgentQueueHealthSamplerTest extends BaseUnitTest {

    @Mock
    private AgentJobRepository jobRepository;

    private MeterRegistry registry;
    private AgentQueueHealthSampler sampler;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        sampler = new AgentQueueHealthSampler(jobRepository, registry);
    }

    /**
     * The case the gauge exists for: a workspace over its monthly LLM cap has every job pushed past
     * {@code available_at}, so {@code agent.queue.depth} — which counts only claimable jobs — reads 0.
     * Without {@code agent.queue.held}, a paused instance and an idle one publish an identical series
     * and no alert can tell them apart.
     */
    @Test
    void publishesHeldSoABudgetPausedBacklogIsNotReadAsAnIdleQueue() {
        when(jobRepository.queueHealthSnapshot(any())).thenReturn(snapshot(0, null, 12, 0));

        sampler.sample();

        assertThat(gauge("agent.queue.depth")).isZero();
        assertThat(gauge("agent.queue.held"))
                .as("12 jobs are parked on a cap, not absent")
                .isEqualTo(12);
    }

    @Test
    void publishesZeroHeldWhenTheQueueIsMerelyIdle() {
        when(jobRepository.queueHealthSnapshot(any())).thenReturn(snapshot(0, null, 0, 0));

        sampler.sample();

        assertThat(gauge("agent.queue.held")).isZero();
    }

    /** A DB blip must not read as "nothing is held" and silence an alert that was firing. */
    @Test
    void keepsTheLastGoodHeldValueWhenTheSampleFails() {
        when(jobRepository.queueHealthSnapshot(any()))
                .thenReturn(snapshot(0, null, 7, 0))
                .thenThrow(new IllegalStateException("connection reset"));

        sampler.sample();
        sampler.sample();

        assertThat(gauge("agent.queue.held")).isEqualTo(7);
        assertThat(registry.get("agent.queue.health.sampler.failures").counter().count())
                .isEqualTo(1);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private static AgentJobRepository.QueueHealthSnapshot snapshot(
            long depth, @Nullable Instant oldestAvailableAt, long held, long running) {
        return new AgentJobRepository.QueueHealthSnapshot() {
            @Override
            public long getDepth() {
                return depth;
            }

            @Override
            public @Nullable Instant getOldestAvailableAt() {
                return oldestAvailableAt;
            }

            @Override
            public long getHeld() {
                return held;
            }

            @Override
            public long getRunning() {
                return running;
            }
        };
    }
}
