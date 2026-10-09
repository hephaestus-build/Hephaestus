package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class LlmConnectionHealthTest extends BaseUnitTest {

    private static final long CONNECTION = 7L;

    private final LlmConnectionHealth health =
            new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults(), Duration.ofMillis(200));

    private void fail(long connection) {
        Objects.requireNonNull(health.acquire(FundingSource.INSTANCE, connection))
                .failed(new IllegalStateException("upstream stalled"));
    }

    @Test
    void shouldRefuseCallsOnlyOnTheConnectionThatFailedRepeatedly() {
        fail(CONNECTION);
        fail(CONNECTION);
        assertThat(health.isOpen(FundingSource.INSTANCE, CONNECTION)).isFalse();

        fail(CONNECTION);

        assertThat(health.isOpen(FundingSource.INSTANCE, CONNECTION)).isTrue();
        assertThat(health.acquire(FundingSource.INSTANCE, CONNECTION)).isNull();
        assertThat(health.acquire(FundingSource.INSTANCE, CONNECTION + 1)).isNotNull();
        assertThat(health.acquire(FundingSource.WORKSPACE, CONNECTION)).isNotNull();
    }

    @Test
    void shouldLetOneCallProbeTheProviderAfterTheCooldownAndCloseWhenItAnswers() {
        fail(CONNECTION);
        fail(CONNECTION);
        fail(CONNECTION);

        await().atMost(Duration.ofSeconds(5)).until(() -> !health.isOpen(FundingSource.INSTANCE, CONNECTION));
        LlmConnectionHealth.Permit probe = health.acquire(FundingSource.INSTANCE, CONNECTION);
        assertThat(probe).as("the probe").isNotNull();
        assertThat(health.acquire(FundingSource.INSTANCE, CONNECTION))
                .as("a second call while the probe runs")
                .isNull();
        Objects.requireNonNull(probe).succeeded();

        assertThat(health.acquire(FundingSource.INSTANCE, CONNECTION)).isNotNull();
    }

    @Test
    void shouldNotCountACallThatSaysNothingAboutTheProvider() {
        for (int call = 0; call < 5; call++) {
            Objects.requireNonNull(health.acquire(FundingSource.INSTANCE, CONNECTION))
                    .released();
        }
        fail(CONNECTION);
        fail(CONNECTION);

        assertThat(health.isOpen(FundingSource.INSTANCE, CONNECTION)).isFalse();
    }
}
