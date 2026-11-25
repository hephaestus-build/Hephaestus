package de.tum.cit.aet.hephaestus.core.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Job;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Outcome;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerSweeps;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RowSweepRule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ClientInputExpiryTest {
    private static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");

    private final OwnerSweeps sweeps = mock(OwnerSweeps.class);
    private final PrivacyJobMetrics metrics = mock(PrivacyJobMetrics.class);
    private final ClientInputExpiry expiry = new ClientInputExpiry(sweeps, metrics, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void shouldDrainABurstInOneRunAndReportSuccessOnceTheLastBatchIsShort() {
        when(sweeps.expire(RowSweepRule.CLIENT_INPUT_EXPIRY, NOW)).thenReturn(1000, 1000, 300);

        expiry.prune();

        verify(sweeps, times(3)).expire(RowSweepRule.CLIENT_INPUT_EXPIRY, NOW);
        verify(metrics).record(Job.CLIENT_INPUT_RETENTION, Outcome.SUCCESS);
        verify(metrics).recordAffected(Job.CLIENT_INPUT_RETENTION, 2300);
    }

    @Test
    void shouldReportSuccessWhenTheBudgetDrainsTheBacklogExactly() {
        when(sweeps.expire(RowSweepRule.CLIENT_INPUT_EXPIRY, NOW))
                .thenReturn(1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 0);

        expiry.prune();

        verify(sweeps, times(13)).expire(RowSweepRule.CLIENT_INPUT_EXPIRY, NOW);
        verify(metrics).record(Job.CLIENT_INPUT_RETENTION, Outcome.SUCCESS);
        verify(metrics).recordAffected(Job.CLIENT_INPUT_RETENTION, 12000);
    }

    @Test
    void shouldReportSuccessWhenTheBatchAfterTheBudgetDrainsTheRest() {
        when(sweeps.expire(RowSweepRule.CLIENT_INPUT_EXPIRY, NOW))
                .thenReturn(1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 300);

        expiry.prune();

        verify(sweeps, times(13)).expire(RowSweepRule.CLIENT_INPUT_EXPIRY, NOW);
        verify(metrics).record(Job.CLIENT_INPUT_RETENTION, Outcome.SUCCESS);
        verify(metrics).recordAffected(Job.CLIENT_INPUT_RETENTION, 12300);
    }

    @Test
    void shouldReportWorkLeftBehindWhenReceiptsRemainAfterTheBudget() {
        when(sweeps.expire(RowSweepRule.CLIENT_INPUT_EXPIRY, NOW)).thenReturn(1000);

        expiry.prune();

        verify(sweeps, times(13)).expire(RowSweepRule.CLIENT_INPUT_EXPIRY, NOW);
        verify(metrics).record(Job.CLIENT_INPUT_RETENTION, Outcome.INCOMPLETE);
        verify(metrics).recordAffected(Job.CLIENT_INPUT_RETENTION, 13000);
    }

    @Test
    void shouldReportAFailedSweepWithTheBatchesItCommittedAndPassTheErrorOn() {
        when(sweeps.expire(RowSweepRule.CLIENT_INPUT_EXPIRY, NOW))
                .thenReturn(1000)
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(expiry::prune).hasMessage("database unavailable");
        verify(metrics).recordAffected(Job.CLIENT_INPUT_RETENTION, 1000);
        verify(metrics).record(Job.CLIENT_INPUT_RETENTION, Outcome.FAILURE);
    }
}
