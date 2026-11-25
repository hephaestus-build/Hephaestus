package de.tum.cit.aet.hephaestus.practices.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.settings.spi.PublicUsagePolicy;
import de.tum.cit.aet.hephaestus.core.settings.spi.ReleaseGate;
import de.tum.cit.aet.hephaestus.core.settings.spi.UsagePeriod;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/** Which half-years a run releases (ADR 0153 decisions 4 to 6). */
class UsageReleaseServiceTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-04-20T00:00:00Z");
    private static final ReleaseGate ON = new ReleaseGate(true, 7);
    private static final ReleaseGate OFF = new ReleaseGate(false, 7);

    @Mock
    private UsageReleaseRepository releases;

    @Mock
    private PublicUsagePolicy policy;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private UsageReleaseService service() {
        return new UsageReleaseService(new UsageReleaser(releases, policy), Clock.fixed(NOW, ZoneOffset.UTC), meters);
    }

    @Test
    void shouldReleaseNoPeriodBeforeItsDueTime() {
        // 2025-10 ends on 1 April 2026 and is due 14 days later, at 2026-04-15T00:00Z.
        assertThat(UsagePeriod.latestReleasable(Instant.parse("2026-04-14T23:59:59Z"))
                        .key())
                .isEqualTo("2025-04");
        assertThat(UsagePeriod.latestReleasable(Instant.parse("2026-04-15T00:00:00Z"))
                        .key())
                .isEqualTo("2025-10");
        assertThat(UsagePeriod.latestReleasable(Instant.parse("2026-10-14T23:59:59Z"))
                        .key())
                .isEqualTo("2025-10");
        assertThat(UsagePeriod.latestReleasable(Instant.parse("2026-10-15T00:00:00Z"))
                        .key())
                .isEqualTo("2026-04");
    }

    @Test
    void shouldComputeNothingWhileThePageIsOff() {
        when(policy.lockForRelease()).thenReturn(OFF);

        assertThat(service().releaseDue()).isZero();
        verify(releases, never()).count(any(), any());
        assertThat(meters.get("practice.usage.release.runs")
                        .tag("outcome", "success")
                        .counter()
                        .count())
                .isOne();
    }

    @Test
    void shouldComputeNothingWhenTheLatestDuePeriodIsStored() {
        when(policy.lockForRelease()).thenReturn(ON);
        when(releases.findLatestPeriodStart()).thenReturn(Optional.of(LocalDate.of(2025, 10, 1)));

        assertThat(service().releaseDue()).isZero();
        verify(releases, never()).count(any(), any());
    }

    @Test
    void shouldReleaseInOrderThePeriodsThatBecameDueWhileThePageWasOff() {
        when(policy.lockForRelease()).thenReturn(OFF, ON);
        when(releases.findLatestPeriodStart()).thenReturn(Optional.of(LocalDate.of(2024, 10, 1)));
        when(policy.unchangedUntilCommit(ON)).thenReturn(true);
        when(releases.count(any(), any())).thenReturn(List.of());
        when(releases.insertOnce(any(), any(), any(), anyShort(), any())).thenReturn(1);

        var service = service();
        assertThat(service.releaseDue()).as("off").isZero();

        assertThat(service.releaseDue()).isEqualTo(2);
        assertThat(meters.get("practice.usage.releases").counter().count())
                .as("each released half-year is counted")
                .isEqualTo(2.0);
        var order = inOrder(releases);
        order.verify(releases).insertOnce(eq(LocalDate.of(2025, 4, 1)), any(), any(), anyShort(), any());
        order.verify(releases).insertOnce(eq(LocalDate.of(2025, 10, 1)), any(), any(), anyShort(), any());
    }

    @Test
    void shouldMakeTheFirstReleaseWhenThePageIsOnAgainAndNothingIsStored() {
        when(policy.lockForRelease()).thenReturn(OFF, ON);
        when(releases.findLatestPeriodStart()).thenReturn(Optional.empty());
        when(releases.count(any(), any())).thenReturn(List.of());

        var service = service();
        assertThat(service.releaseDue()).as("off").isZero();
        verify(releases, never()).count(any(), any());

        assertThat(service.releaseDue()).as("no practice review yet").isZero();
        verify(releases)
                .count(
                        UsageReleaser.BEGINNING.start().atStartOfDay(),
                        LocalDate.of(2025, 10, 1).atStartOfDay());
        verify(releases, never()).insertOnce(any(), any(), any(), anyShort(), any());
    }

    @Test
    void shouldDiscardTheRunWhenTheSettingsChangedWhileItCounted() {
        when(policy.lockForRelease()).thenReturn(ON);
        when(policy.unchangedUntilCommit(ON)).thenReturn(false);
        when(releases.findLatestPeriodStart()).thenReturn(Optional.empty());
        when(releases.count(any(), any()))
                .thenReturn(List.of(new UsageCount("2025-10", "PRACTICE_REVIEWS", 10, 100, 10)));
        when(releases.insertOnce(any(), any(), any(), anyShort(), any())).thenReturn(1);

        // The releaser throws, which rolls back what it staged; the run is not a failure.
        assertThatThrownBy(() -> new UsageReleaser(releases, policy).releaseDue(NOW))
                .isInstanceOf(ReleaseDiscarded.class);
        assertThat(service().releaseDue()).isZero();
        assertThat(meters.get("practice.usage.release.runs")
                        .tag("outcome", "success")
                        .counter()
                        .count())
                .isOne();
    }

    @Test
    void shouldCountAFailedRun() {
        when(policy.lockForRelease()).thenThrow(new IllegalStateException("database down"));

        try {
            service().releaseDue();
        } catch (IllegalStateException expected) {
            // The job rethrows, and the counter shows it.
        }
        assertThat(meters.get("practice.usage.release.runs")
                        .tag("outcome", "failure")
                        .counter()
                        .count())
                .isOne();
    }
}
