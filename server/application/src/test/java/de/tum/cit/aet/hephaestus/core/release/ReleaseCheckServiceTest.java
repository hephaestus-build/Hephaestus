package de.tum.cit.aet.hephaestus.core.release;

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
    private static final Instant PUBLISHED = Instant.parse("2026-09-01T00:00:00Z");

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
        var properties = new ReleaseProperties(RunningReleaseTest.COMMIT, RunningReleaseTest.IMAGE, enabled);
        return new ReleaseCheckService(RunningReleaseTest.running(version), client, clock, properties);
    }

    private static Found found(String version) {
        return new Found(
                new LatestReleaseDTO(
                        version,
                        PUBLISHED,
                        "https://github.com/hephaestus-build/Hephaestus/releases/tag/v" + version,
                        null),
                "\"etag\"");
    }

    @Test
    void shouldDistinguishNeverCheckedFromCurrentAndReuseTheAnswerForADay() {
        var service = service("1.2.3", true);
        assertThat(service.status().status()).isEqualTo(ReleaseCheckStatus.NEVER_CHECKED);
        when(client.fetchLatest(null)).thenReturn(found("1.2.3"));
        service.poll();
        var checked = service.status();
        assertThat(checked.status()).isEqualTo(ReleaseCheckStatus.CURRENT);
        assertThat(checked.lastAttempt()).isEqualTo(NOW);
        assertThat(checked.lastSuccess()).isEqualTo(NOW);
        assertThat(checked.nextCheck()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        clock.advance(Duration.ofHours(23));
        service.poll();
        verify(client, times(1)).fetchLatest(null);
    }

    @Test
    void shouldLetAnAdministratorBypassTheCacheButNotARateLimitWindow() {
        var service = service("1.2.3", true);
        when(client.fetchLatest(null)).thenReturn(found("1.2.3"));
        service.poll();
        when(client.fetchLatest("\"etag\"")).thenReturn(found("1.3.0"));
        assertThat(service.check().status()).isEqualTo(ReleaseCheckStatus.UPDATE_AVAILABLE);
        when(client.fetchLatest("\"etag\""))
                .thenReturn(new Failed(ReleaseCheckFailure.RATE_LIMITED, NOW.plusSeconds(7200)));
        var limited = service.check();
        assertThat(limited.status()).isEqualTo(ReleaseCheckStatus.FAILED);
        assertThat(limited.failure()).isEqualTo(ReleaseCheckFailure.RATE_LIMITED);
        assertThat(limited.nextCheck()).isEqualTo(NOW.plusSeconds(7200));
        clock.advance(Duration.ofSeconds(3600));
        assertThat(service.check()).isEqualTo(limited);
        verify(client, times(2)).fetchLatest("\"etag\"");
    }

    @Test
    void shouldRetainTheLastAnswerAndBackOffWhenGitHubIsUnavailable() {
        var service = service("1.2.3", true);
        when(client.fetchLatest(null)).thenReturn(found("1.3.0"));
        service.poll();
        clock.advance(Duration.ofHours(24));
        when(client.fetchLatest("\"etag\"")).thenReturn(new Failed(ReleaseCheckFailure.UNAVAILABLE, null));
        service.poll();
        var failed = service.status();
        assertThat(failed.status()).isEqualTo(ReleaseCheckStatus.FAILED);
        assertThat(failed.failure()).isEqualTo(ReleaseCheckFailure.UNAVAILABLE);
        assertThat(failed.lastSuccess()).isEqualTo(NOW);
        assertThat(failed.lastAttempt()).isEqualTo(clock.instant());
        assertThat(failed.latest()).isNotNull();
        assertThat(failed.nextCheck()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        clock.advance(Duration.ofMinutes(15));
        service.poll();
        assertThat(service.status().nextCheck()).isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));
        verify(client, times(2)).fetchLatest("\"etag\"");
    }

    @Test
    void shouldCapTheBackoffAtTheCachePeriod() {
        var service = service("1.2.3", true);
        when(client.fetchLatest(null)).thenReturn(new Failed(ReleaseCheckFailure.UNAVAILABLE, null));
        for (int attempt = 0; attempt < 10; attempt++) {
            service.check();
        }
        assertThat(service.status().nextCheck()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void shouldRecoverAfterAFailureAndResetTheBackoff() {
        var service = service("1.2.3", true);
        when(client.fetchLatest(null)).thenReturn(new Failed(ReleaseCheckFailure.UNAVAILABLE, null));
        service.poll();
        clock.advance(Duration.ofMinutes(15));
        when(client.fetchLatest(null)).thenReturn(found("1.2.4"));
        service.poll();
        var recovered = service.status();
        assertThat(recovered.status()).isEqualTo(ReleaseCheckStatus.UPDATE_AVAILABLE);
        assertThat(recovered.failure()).isNull();
        assertThat(recovered.lastSuccess()).isEqualTo(clock.instant());
        assertThat(recovered.nextCheck()).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
    }

    @Test
    void shouldRevalidateWithTheEtagAndFailWhenNothingIsCachedBehindIt() {
        var service = service("1.2.3", true);
        when(client.fetchLatest(null)).thenReturn(new NotModified());
        service.poll();
        assertThat(service.status().status()).isEqualTo(ReleaseCheckStatus.FAILED);
        assertThat(service.status().failure()).isEqualTo(ReleaseCheckFailure.MALFORMED);
        when(client.fetchLatest(null)).thenReturn(found("1.3.0"));
        service.check();
        clock.advance(Duration.ofHours(24));
        when(client.fetchLatest("\"etag\"")).thenReturn(new NotModified());
        service.poll();
        var revalidated = service.status();
        assertThat(revalidated.status()).isEqualTo(ReleaseCheckStatus.UPDATE_AVAILABLE);
        assertThat(revalidated.lastSuccess()).isEqualTo(clock.instant());
    }

    @Test
    void shouldNeverContactGitHubWhenDisabledOrNotRunningARelease() {
        for (String version : new String[] {"0.0.0-development", RunningReleaseTest.COMMIT}) {
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
    void shouldReportCurrentRatherThanADowngradeWhenThePublishedReleaseIsOlder() {
        var service = service("2.0.0", true);
        when(client.fetchLatest(null)).thenReturn(found("1.3.0"));
        var status = service.check();
        assertThat(status.status()).isEqualTo(ReleaseCheckStatus.CURRENT);
        assertThat(status.latest()).isNotNull();
        assertThat(status.latest().version()).isEqualTo("1.3.0");
    }

    @Test
    void shouldServeCachedReadsWhileACheckIsInFlightAndSerialiseManualChecks() throws Exception {
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
