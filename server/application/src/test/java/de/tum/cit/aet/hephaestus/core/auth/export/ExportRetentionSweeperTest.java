package de.tum.cit.aet.hephaestus.core.auth.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerSweeps;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RowSweepRule;
import de.tum.cit.aet.hephaestus.integration.core.sync.MutableClock;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ExportRetentionSweeperTest extends BaseUnitTest {

    @Mock
    private OwnerSweeps ownerSweeps;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PrivacyJobMetrics metrics = new PrivacyJobMetrics(registry);

    private final MutableClock clock = MutableClock.atFixedInstant();
    private ExportRetentionSweeper sweeper;

    @BeforeEach
    void setUp() {
        sweeper = new ExportRetentionSweeper(ownerSweeps, metrics, clock);
    }

    @Test
    void shouldRecordSuccessAndTheExpiredCountWhenTheSweepRuns() {
        when(ownerSweeps.expire(eq(RowSweepRule.EXPIRED_EXPORTS), any(Instant.class)))
                .thenReturn(ExportRetentionSweeper.BATCH, 4);

        sweeper.sweep();

        verify(ownerSweeps, times(2)).expire(eq(RowSweepRule.EXPIRED_EXPORTS), any(Instant.class));

        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_retention", "outcome", "success")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("privacy.job.affected")
                        .tag("job", "export_retention")
                        .counter()
                        .count())
                .isEqualTo(ExportRetentionSweeper.BATCH + 4);
    }

    @Test
    void shouldStartNoBatchAfterTheBudgetAndRecordThatWorkRemains() {
        when(ownerSweeps.expire(eq(RowSweepRule.EXPIRED_EXPORTS), any(Instant.class)))
                .thenAnswer(invocation -> {
                    clock.advance(Duration.ofMinutes(1));
                    return ExportRetentionSweeper.BATCH;
                });

        sweeper.sweep();

        verify(ownerSweeps, times((int) ExportRetentionSweeper.BUDGET.toMinutes()))
                .expire(eq(RowSweepRule.EXPIRED_EXPORTS), any(Instant.class));
        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_retention", "outcome", "incomplete")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldRecordSuccessWhenTheLastBatchIsNotFull() {
        when(ownerSweeps.expire(eq(RowSweepRule.EXPIRED_EXPORTS), any(Instant.class)))
                .thenReturn(ExportRetentionSweeper.BATCH, ExportRetentionSweeper.BATCH, 0);

        sweeper.sweep();

        verify(ownerSweeps, times(3)).expire(eq(RowSweepRule.EXPIRED_EXPORTS), any(Instant.class));
        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_retention", "outcome", "success")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldRecordSuccessWhenNothingWasEligible() {
        when(ownerSweeps.expire(eq(RowSweepRule.EXPIRED_EXPORTS), any(Instant.class)))
                .thenReturn(0);

        sweeper.sweep();

        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_retention", "outcome", "success")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("privacy.job.affected")
                        .tag("job", "export_retention")
                        .counter()
                        .count())
                .isEqualTo(0);
    }

    @Test
    void shouldRecordFailureAndPropagateWhenTheSweepFails() {
        when(ownerSweeps.expire(eq(RowSweepRule.EXPIRED_EXPORTS), any(Instant.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(sweeper::sweep).isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "export_retention", "outcome", "failure")
                        .counter()
                        .count())
                .isEqualTo(1);
    }
}
