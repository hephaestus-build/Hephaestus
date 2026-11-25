package de.tum.cit.aet.hephaestus.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Job;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Outcome;
import de.tum.cit.aet.hephaestus.core.database.ClientInputRepository;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.web.ClientInputs.FingerprintInput;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class ClientInputsTest {
    @Test
    void shouldEnforceTheRetryWindowAndClockSkew() {
        Instant now = Instant.parse("2026-11-24T00:00:00Z");
        var inputs = new ClientInputs(
                mock(ClientInputRepository.class),
                mock(OwnerScope.class),
                mock(PrivacyJobMetrics.class),
                JsonMapper.shared(),
                Clock.fixed(now, ZoneOffset.UTC));
        Instant oldest = now.minus(Duration.ofDays(3));
        Instant newest = now.plus(Duration.ofMinutes(5));

        inputs.validateAge(keyAt(oldest));
        inputs.validateAge(keyAt(newest));
        assertThatThrownBy(() -> inputs.validateAge(keyAt(oldest.minusMillis(1))))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> inputs.validateAge(keyAt(newest.plusMillis(1))))
                .isInstanceOf(InvalidInputException.class);
    }

    private static UUID keyAt(Instant time) {
        return new UUID((time.toEpochMilli() << 16) | 0x7000L, Long.MIN_VALUE);
    }

    @Test
    void shouldDrainABurstInOneRunAndReportSuccessOnceTheLastBatchIsShort() {
        var receipts = mock(ClientInputRepository.class);
        var metrics = mock(PrivacyJobMetrics.class);
        when(receipts.prune()).thenReturn(1000, 1000, 300);

        inputs(receipts, metrics).prune();

        verify(receipts, times(3)).prune();
        verify(metrics).record(Job.CLIENT_INPUT_RETENTION, Outcome.SUCCESS);
        verify(metrics).recordAffected(Job.CLIENT_INPUT_RETENTION, 2300);
    }

    @Test
    void shouldReportSuccessWhenTheBudgetDrainsTheBacklogExactly() {
        var receipts = mock(ClientInputRepository.class);
        var metrics = mock(PrivacyJobMetrics.class);
        when(receipts.prune()).thenReturn(1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 0);

        inputs(receipts, metrics).prune();

        verify(receipts, times(13)).prune();
        verify(metrics).record(Job.CLIENT_INPUT_RETENTION, Outcome.SUCCESS);
        verify(metrics).recordAffected(Job.CLIENT_INPUT_RETENTION, 12000);
    }

    @Test
    void shouldReportSuccessWhenTheBatchAfterTheBudgetDrainsTheRest() {
        var receipts = mock(ClientInputRepository.class);
        var metrics = mock(PrivacyJobMetrics.class);
        when(receipts.prune()).thenReturn(1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 300);

        inputs(receipts, metrics).prune();

        verify(receipts, times(13)).prune();
        verify(metrics).record(Job.CLIENT_INPUT_RETENTION, Outcome.SUCCESS);
        verify(metrics).recordAffected(Job.CLIENT_INPUT_RETENTION, 12300);
    }

    @Test
    void shouldReportWorkLeftBehindWhenReceiptsRemainAfterTheBudget() {
        var receipts = mock(ClientInputRepository.class);
        var metrics = mock(PrivacyJobMetrics.class);
        when(receipts.prune()).thenReturn(1000);

        inputs(receipts, metrics).prune();

        verify(receipts, times(13)).prune();
        verify(metrics).record(Job.CLIENT_INPUT_RETENTION, Outcome.INCOMPLETE);
        verify(metrics).recordAffected(Job.CLIENT_INPUT_RETENTION, 13000);
    }

    @Test
    void shouldReportAFailedSweepWithTheBatchesItCommittedAndPassTheErrorOn() {
        var receipts = mock(ClientInputRepository.class);
        var metrics = mock(PrivacyJobMetrics.class);
        when(receipts.prune()).thenReturn(1000).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> inputs(receipts, metrics).prune()).hasMessage("database unavailable");
        verify(metrics).recordAffected(Job.CLIENT_INPUT_RETENTION, 1000);
        verify(metrics).record(Job.CLIENT_INPUT_RETENTION, Outcome.FAILURE);
    }

    private static ClientInputs inputs(ClientInputRepository receipts, PrivacyJobMetrics metrics) {
        return new ClientInputs(receipts, mock(OwnerScope.class), metrics, JsonMapper.shared(), Clock.systemUTC());
    }

    @Test
    void shouldIncludeAttachmentHashesInTheFingerprint() {
        var inputs = new ClientInputs(
                mock(ClientInputRepository.class),
                mock(OwnerScope.class),
                mock(PrivacyJobMetrics.class),
                JsonMapper.shared(),
                Clock.systemUTC());
        var request = new FingerprintInput("submit", "A message", List.of("a".repeat(64)));
        var first = inputs.fingerprint(request);
        assertThat(inputs.fingerprint(request)).isEqualTo(first);
        assertThat(inputs.fingerprint(new FingerprintInput("submit", "A message", List.of("b".repeat(64)))))
                .isNotEqualTo(first);
    }
}
