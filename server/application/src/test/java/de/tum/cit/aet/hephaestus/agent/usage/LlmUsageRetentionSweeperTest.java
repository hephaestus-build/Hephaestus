package de.tum.cit.aet.hephaestus.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class LlmUsageRetentionSweeperTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");
    private static final int BATCH_SIZE = 500;

    @Mock
    private LlmUsageEventRepository repository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private SimpleMeterRegistry registry;
    private LlmUsageRetentionSweeper sweeper;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        sweeper = new LlmUsageRetentionSweeper(
                repository,
                new LlmUsageProperties(Duration.ofDays(400)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                transactionTemplate,
                new PrivacyJobMetrics(registry));
    }

    @Test
    void shouldDeleteEverythingOlderThanTheConfiguredWindow() {
        when(repository.deleteExpired(any(), anyInt())).thenReturn(7);

        assertThat(sweeper.sweepNow()).isEqualTo(7);

        verify(repository).deleteExpired(Instant.parse("2025-07-30T12:00:00Z"), BATCH_SIZE);
        assertThat(counter("privacy.job.completed", "outcome", "success")).isEqualTo(1);
        assertThat(affected()).isEqualTo(7);
    }

    @Test
    void shouldKeepDeletingWhileABatchComesBackFull() {
        when(repository.deleteExpired(any(), anyInt())).thenReturn(BATCH_SIZE, BATCH_SIZE, 12);

        assertThat(sweeper.sweepNow()).isEqualTo(BATCH_SIZE + BATCH_SIZE + 12L);

        verify(repository, times(3)).deleteExpired(any(), eq(BATCH_SIZE));
        assertThat(affected()).isEqualTo(BATCH_SIZE + BATCH_SIZE + 12d);
    }

    @Test
    void shouldReportIncompleteRatherThanSuccessWhenTheTimeBudgetLeavesBacklog() {
        when(repository.deleteExpired(any(), anyInt())).thenReturn(BATCH_SIZE);
        LlmUsageRetentionSweeper budgeted = new LlmUsageRetentionSweeper(
                repository,
                new LlmUsageProperties(Duration.ofDays(400)),
                new SteppingClock(Duration.ofMinutes(6)),
                transactionTemplate,
                new PrivacyJobMetrics(registry));

        assertThat(budgeted.sweepNow()).isEqualTo(BATCH_SIZE);

        assertThat(counter("privacy.job.completed", "outcome", "incomplete")).isEqualTo(1);
        assertThat(counter("privacy.job.completed", "outcome", "success")).isZero();
    }

    @Test
    void shouldReportFailureAndPropagateWhenDeletionFails() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository)
                .deleteExpired(any(), anyInt());

        assertThatThrownBy(sweeper::sweep).isInstanceOf(IllegalStateException.class);

        assertThat(counter("privacy.job.completed", "outcome", "failure")).isEqualTo(1);
    }

    private double counter(String name, String key, String value) {
        return registry.get(name)
                .tags("job", "llm_usage_retention", key, value)
                .counter()
                .count();
    }

    private double affected() {
        return registry.get("privacy.job.affected")
                .tag("job", "llm_usage_retention")
                .counter()
                .count();
    }

    /** Moves forward on every read, so a pass over full batches reaches its time budget. */
    private static final class SteppingClock extends Clock {

        private final Duration step;
        private Instant now = NOW;

        private SteppingClock(Duration step) {
            this.step = step;
        }

        @Override
        public Instant instant() {
            Instant reading = now;
            now = now.plus(step);
            return reading;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
