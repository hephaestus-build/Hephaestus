package de.tum.cit.aet.hephaestus.core.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@Tag("unit")
class ReleaseCheckServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private final ReleaseCheckClient client = mock(ReleaseCheckClient.class);
    private final Clock clock = mock(Clock.class);

    private ReleaseCheckService service(String version, boolean enabled) {
        when(clock.instant()).thenReturn(NOW);
        return new ReleaseCheckService(
                RunningReleaseTest.identity(
                        version,
                        RunningReleaseTest.COMMIT,
                        new MockEnvironment()
                                .withProperty(
                                        "HEPHAESTUS_DEPLOYMENT_IDENTITY",
                                        RunningReleaseTest.projection(version, RunningReleaseTest.COMMIT))),
                client,
                clock,
                enabled);
    }

    private static ReleaseCheckClient.Result release(String version) {
        return new ReleaseCheckClient.Result(
                200,
                "\"etag\"",
                null,
                null,
                new ReleaseStatusDTO.AvailableReleaseDTO(
                        version,
                        "https://github.com/hephaestus-build/Hephaestus/releases/tag/v" + version,
                        "UNKNOWN",
                        "UNKNOWN",
                        "Review migration notes"));
    }

    @Test
    void shouldDistinguishNeverCheckedFromCurrentAndReuseCache() {
        var service = service("1.2.3", true);
        assertThat(service.status().status()).isEqualTo("NEVER_CHECKED");
        when(client.check(null)).thenReturn(release("1.2.3"));
        assertThat(service.refresh().status()).isEqualTo("CURRENT");
        service.refresh();
        verify(client, times(1)).check(null);
        assertThat(service.status().lastSuccess()).isEqualTo(NOW);
    }

    @Test
    void shouldRetainLastSuccessAndRedactFailureWhenSourceFails() {
        var service = service("1.2.3", true);
        when(client.check(null)).thenReturn(release("1.3.0"));
        assertThat(service.refresh().status()).isEqualTo("UPDATE_AVAILABLE");
        when(clock.instant()).thenReturn(NOW.plusSeconds(86400));
        assertThat(service.status().status()).isEqualTo("STALE");
        when(client.check("\"etag\"")).thenThrow(new IllegalStateException("secret tenant proxy response"));
        var failed = service.refresh();
        assertThat(failed.status()).isEqualTo("CHECK_FAILED");
        assertThat(failed.lastSuccess()).isEqualTo(NOW);
        assertThat(failed.available()).isNotNull();
        assertThat(failed.toString()).doesNotContain("secret tenant");
        service.refresh();
        verify(client, times(1)).check("\"etag\"");
    }

    @Test
    void shouldRevalidateConditionalResponse() {
        var service = service("1.2.3", true);
        when(client.check(null)).thenReturn(release("1.3.0"));
        service.refresh();
        when(clock.instant()).thenReturn(NOW.plusSeconds(86400));
        when(client.check("\"etag\"")).thenReturn(new ReleaseCheckClient.Result(304, null, null, null, null));
        assertThat(service.refresh().status()).isEqualTo("UPDATE_AVAILABLE");
        assertThat(service.status().lastSuccess()).isEqualTo(NOW.plusSeconds(86400));
    }

    @Test
    void shouldHonorRateLimitForManualRefresh() {
        var service = service("1.2.3", true);
        when(client.check(null)).thenReturn(new ReleaseCheckClient.Result(429, null, "7200", null, null));
        var result = service.refresh();
        assertThat(result.failureReason()).isEqualTo("RATE_LIMITED");
        assertThat(result.nextCheck()).isEqualTo(NOW.plusSeconds(7200));
        when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
        service.refresh();
        verify(client, times(1)).check(null);
    }

    @Test
    void shouldNotContactGitHubWhenDisabledOrPrereleaseOrLocal() {
        for (String version : new String[] {"0.0.0-development", "1.0.0-rc.1", "unknown"}) {
            assertThat(service(version, true).refresh().status()).isEqualTo("UNSUPPORTED");
        }
        assertThat(service("1.2.3", false).refresh().status()).isEqualTo("DISABLED");
        verifyNoInteractions(client);
    }

    @Test
    void shouldNotCheckPackagedArtifactVersionWithoutDeploymentIdentity() {
        when(clock.instant()).thenReturn(NOW);
        for (String commit : new String[] {"unknown", RunningReleaseTest.COMMIT}) {
            var service = new ReleaseCheckService(
                    RunningReleaseTest.identity("0.77.4", commit, new MockEnvironment()), client, clock, true);
            assertThat(service.refresh().status()).isEqualTo("UNSUPPORTED");
            assertThat(service.status().lastAttempt()).isNull();
        }
        verifyNoInteractions(client);
    }

    @Test
    void shouldNotRecommendDowngradingWhenPublishedReleaseIsOlder() {
        var service = service("2.0.0", true);
        when(client.check(null)).thenReturn(release("1.3.0"));
        assertThat(service.refresh().status()).isEqualTo("UNSUPPORTED");
    }

    @Test
    void shouldFailWhenNotModifiedHasNoCachedBody() {
        var service = service("1.2.3", true);
        when(client.check(null)).thenReturn(new ReleaseCheckClient.Result(304, null, null, null, null));
        assertThat(service.refresh().status()).isEqualTo("CHECK_FAILED");
    }

    @Test
    void shouldHonorHttpDatesAndResetAndHandleMalformedHeaders() {
        assertThat(ReleaseCheckService.retryAt("Tue, 8 Sep 2026 02:00:00 GMT", null, NOW))
                .isEqualTo(NOW.plusSeconds(7200));
        assertThat(ReleaseCheckService.retryAt(
                        "60", Long.toString(NOW.plusSeconds(7200).getEpochSecond()), NOW))
                .isEqualTo(NOW.plusSeconds(7200));
        assertThat(ReleaseCheckService.retryAt("not a date", null, NOW)).isEqualTo(NOW.plusSeconds(86400));
    }

    @Test
    void shouldHonorValidRateLimitWhenOtherHeaderIsMalformed() {
        assertThat(ReleaseCheckService.retryAt(
                        "invalid", Long.toString(NOW.plusSeconds(172800).getEpochSecond()), NOW))
                .isEqualTo(NOW.plusSeconds(172800));
        assertThat(ReleaseCheckService.retryAt("172800", "invalid", NOW)).isEqualTo(NOW.plusSeconds(172800));
    }

    @Test
    void shouldNotMislabelForbiddenResponsesAsRateLimits() {
        var service = service("1.2.3", true);
        when(client.check(null)).thenReturn(new ReleaseCheckClient.Result(403, null, null, null, null));
        assertThat(service.refresh().failureReason()).isEqualTo("RELEASE_SOURCE_UNAVAILABLE");
    }

    @Test
    void shouldRecoverAfterBackoffWithoutLosingLastAttempt() {
        var service = service("1.2.3", true);
        when(client.check(null)).thenReturn(new ReleaseCheckClient.Result(503, null, null, null, null));
        assertThat(service.refresh().status()).isEqualTo("CHECK_FAILED");
        when(clock.instant()).thenReturn(NOW.plusSeconds(900));
        when(client.check(null)).thenReturn(release("1.2.4"));
        var recovered = service.refresh();
        assertThat(recovered.status()).isEqualTo("UPDATE_AVAILABLE");
        assertThat(recovered.failureReason()).isNull();
        assertThat(recovered.lastAttempt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(recovered.lastSuccess()).isEqualTo(NOW.plusSeconds(900));
    }

    @Test
    void shouldCoalesceConcurrentManualChecks() throws Exception {
        var service = service("1.2.3", true);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(client.check(null)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("test deadline");
            return release("1.2.4");
        });
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(service::refresh);
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(executor.submit(service::status)
                            .get(1, java.util.concurrent.TimeUnit.SECONDS)
                            .status())
                    .isEqualTo("NEVER_CHECKED");
            var second = executor.submit(service::refresh);
            release.countDown();
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS).status())
                    .isEqualTo("UPDATE_AVAILABLE");
            assertThat(second.get(5, java.util.concurrent.TimeUnit.SECONDS).status())
                    .isEqualTo("UPDATE_AVAILABLE");
        } finally {
            release.countDown();
        }
        verify(client, times(1)).check(null);
    }

    @Test
    void shouldNotEraseKnownUpdateWhenSourceRegresses() {
        var service = service("1.2.3", true);
        when(client.check(null)).thenReturn(release("1.3.0"));
        service.refresh();
        when(clock.instant()).thenReturn(NOW.plusSeconds(86400));
        when(client.check("\"etag\"")).thenReturn(release("1.2.3"));
        var result = service.refresh();
        assertThat(result.status()).isEqualTo("CHECK_FAILED");
        var available = result.available();
        assertThat(available).isNotNull();
        assertThat(available.version()).isEqualTo("1.3.0");
    }
}
