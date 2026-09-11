package de.tum.cit.aet.hephaestus.core.release;

import static de.tum.cit.aet.hephaestus.core.release.ReleaseFixtures.running;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.release.ReleaseCheckClient.Failed;
import de.tum.cit.aet.hephaestus.core.release.ReleaseCheckClient.Found;
import de.tum.cit.aet.hephaestus.core.release.ReleaseCheckClient.NotModified;
import de.tum.cit.aet.hephaestus.core.release.ReleaseStatusDTO.LatestReleaseDTO;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ReleaseCheckServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final Duration DAY = Duration.ofHours(24);

    /** Advances only when told, so cache and backoff windows are exercised without sleeping. */
    private static final class SteppingClock extends Clock {
        private Instant instant = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }

    private final ReleaseCheckClient client = mock(ReleaseCheckClient.class);
    private final SteppingClock clock = new SteppingClock();

    private ReleaseCheckService service(String version, boolean enabled) {
        var properties = new ReleaseProperties(ReleaseFixtures.COMMIT, ReleaseFixtures.IMAGE, enabled);
        return new ReleaseCheckService(running(version), client, clock, properties);
    }

    private static Found found(String version) {
        return new Found(
                new LatestReleaseDTO(
                        version,
                        Instant.parse("2026-09-01T00:00:00Z"),
                        "https://github.com/hephaestus-build/Hephaestus/releases/tag/v" + version,
                        null),
                "\"etag\"");
    }

    private static final Failed OUTAGE = new Failed(ReleaseCheckFailure.UNAVAILABLE, null);

    @Test
    void shouldReuseACompletedAnswerForADayThenRevalidateWithTheEtag() {
        var service = service("1.2.3", true);
        assertThat(service.status().status()).isEqualTo(ReleaseCheckStatus.NEVER_CHECKED);
        when(client.fetchLatest(null)).thenReturn(found("1.3.0"));
        service.poll();
        assertThat(service.status().status()).isEqualTo(ReleaseCheckStatus.UPDATE_AVAILABLE);
        assertThat(service.status().nextCheck()).isEqualTo(NOW.plus(DAY));
        clock.advance(Duration.ofHours(23));
        service.poll();
        clock.advance(Duration.ofHours(1));
        when(client.fetchLatest("\"etag\"")).thenReturn(new NotModified());
        service.poll();
        var revalidated = service.status();
        assertThat(revalidated.status()).isEqualTo(ReleaseCheckStatus.UPDATE_AVAILABLE);
        assertThat(revalidated.lastSuccess()).isEqualTo(NOW.plus(DAY));
        verify(client, times(1)).fetchLatest(null);
    }

    @Test
    void shouldReportCurrentWhenNoNewerReleaseIsPublished() {
        var service = service("1.2.3", true);
        when(client.fetchLatest(null)).thenReturn(found("1.2.3"));
        assertThat(service.check().status()).isEqualTo(ReleaseCheckStatus.CURRENT);
        when(client.fetchLatest("\"etag\"")).thenReturn(found("1.1.0"));
        assertThat(service.check().status()).isEqualTo(ReleaseCheckStatus.CURRENT);
    }

    @Test
    void shouldRetainTheLastAnswerAndDoubleTheBackoffWhileGitHubIsUnavailable() {
        var service = service("1.2.3", true);
        when(client.fetchLatest(null)).thenReturn(found("1.3.0"));
        service.poll();
        clock.advance(DAY);
        when(client.fetchLatest("\"etag\"")).thenReturn(OUTAGE);
        service.poll();
        var failed = service.status();
        assertThat(failed.status()).isEqualTo(ReleaseCheckStatus.FAILED);
        assertThat(failed.failure()).isEqualTo(ReleaseCheckFailure.UNAVAILABLE);
        assertThat(failed.lastSuccess()).isEqualTo(NOW);
        assertThat(failed.latest()).isNotNull();
        assertThat(failed.nextCheck()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        clock.advance(Duration.ofMinutes(15));
        service.poll();
        assertThat(service.status().nextCheck()).isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));
        for (int attempt = 0; attempt < 10; attempt++) {
            clock.advance(DAY);
            service.poll();
        }
        assertThat(service.status().nextCheck()).isEqualTo(clock.instant().plus(DAY));
        clock.advance(DAY);
        when(client.fetchLatest("\"etag\"")).thenReturn(found("1.3.0"));
        service.poll();
        assertThat(service.status().failure()).isNull();
        assertThat(service.status().nextCheck()).isEqualTo(clock.instant().plus(DAY));
    }

    @Test
    void shouldLetAManualCheckSkipTheCacheWithoutMovingOrEscalatingTheAutomaticRetry() {
        var service = service("1.2.3", true);
        when(client.fetchLatest(null)).thenReturn(OUTAGE);
        service.poll();
        Instant scheduled = service.status().nextCheck();
        clock.advance(Duration.ofMinutes(5));
        service.check();
        service.check();
        assertThat(service.status().nextCheck()).isEqualTo(scheduled);
        clock.advance(Duration.ofMinutes(10));
        service.poll();
        assertThat(service.status().nextCheck()).isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));
        verify(client, times(4)).fetchLatest(null);
    }

    @Test
    void shouldRefuseAManualCheckOnlyInsideTheWindowGitHubNamed() {
        var service = service("1.2.3", true);
        when(client.fetchLatest(null)).thenReturn(new Failed(ReleaseCheckFailure.RATE_LIMITED, NOW.plusSeconds(7200)));
        var limited = service.check();
        assertThat(limited.failure()).isEqualTo(ReleaseCheckFailure.RATE_LIMITED);
        assertThat(limited.retryUntil()).isEqualTo(NOW.plusSeconds(7200));
        clock.advance(Duration.ofSeconds(3600));
        assertThat(service.check()).isEqualTo(limited);
        clock.advance(Duration.ofSeconds(3600));
        when(client.fetchLatest(null)).thenReturn(found("1.2.3"));
        assertThat(service.check().status()).isEqualTo(ReleaseCheckStatus.CURRENT);
        assertThat(service.status().retryUntil()).isNull();
    }

    @Test
    void shouldTreatAnUnconditionalNotModifiedAsMalformed() {
        var service = service("1.2.3", true);
        when(client.fetchLatest(null)).thenReturn(new NotModified());
        assertThat(service.check().failure()).isEqualTo(ReleaseCheckFailure.MALFORMED);
    }

    @Test
    void shouldNeverContactGitHubWhenDisabledOrNotRunningARelease() {
        for (String version : new String[] {"0.0.0-development", ReleaseFixtures.COMMIT}) {
            var service = service(version, true);
            assertThat(service.check().status()).isEqualTo(ReleaseCheckStatus.NOT_APPLICABLE);
            service.poll();
        }
        var disabled = service("1.2.3", false);
        assertThat(disabled.check().status()).isEqualTo(ReleaseCheckStatus.DISABLED);
        disabled.poll();
        assertThat(disabled.status().lastAttempt()).isNull();
        verifyNoInteractions(client);
    }

    @Test
    void shouldServeCachedReadsWhileACheckIsInFlightAndSerialiseChecks() throws Exception {
        var service = service("1.2.3", true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.fetchLatest(null)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test deadline");
            return found("1.2.4");
        });
        when(client.fetchLatest("\"etag\"")).thenReturn(found("1.2.4"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(service::check);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.submit(service::status).get(1, TimeUnit.SECONDS).status())
                    .isEqualTo(ReleaseCheckStatus.NEVER_CHECKED);
            var second = executor.submit(service::check);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(ReleaseCheckStatus.UPDATE_AVAILABLE);
            assertThat(second.get(5, TimeUnit.SECONDS).status()).isEqualTo(ReleaseCheckStatus.UPDATE_AVAILABLE);
        } finally {
            release.countDown();
        }
        verify(client, times(1)).fetchLatest(null);
        verify(client, times(1)).fetchLatest("\"etag\"");
    }
}
