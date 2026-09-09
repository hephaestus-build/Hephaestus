package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.SilentModeGraphQlClientFactory;
import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationTokenProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRateLimit;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;

class GitHubGraphQlClientProviderTest extends BaseUnitTest {

    @Mock
    private HttpGraphQlClient baseClient;

    @Mock
    private InstallationTokenProvider tokenProvider;

    @Mock
    private GitHubAppTokenService appTokens;

    @Mock
    private CircuitBreaker circuitBreaker;

    @Mock
    private RateLimitTracker rateLimitTracker;

    @Mock
    private GitHubRestRateLimitSeeder rateLimitSeeder;

    @Mock
    private SilentModeGraphQlClientFactory clientFactory;

    private GitHubGraphQlClientProvider provider;

    @BeforeEach
    void setUp() {
        provider = new GitHubGraphQlClientProvider(
                baseClient,
                new GitHubTokenService(tokenProvider, appTokens),
                circuitBreaker,
                rateLimitTracker,
                rateLimitSeeder,
                clientFactory);
    }

    @Test
    void shouldReturnGuardedClientWhenPersonalAccessTokenScopeIsActive() {
        when(tokenProvider.isScopeActive(42L)).thenReturn(true);
        when(tokenProvider.getAuthMode(42L)).thenReturn(AuthMode.PERSONAL_ACCESS_TOKEN);
        when(tokenProvider.getPersonalAccessToken(42L)).thenReturn(Optional.of("github-token"));
        HttpGraphQlClient guardedClient = Mockito.mock(HttpGraphQlClient.class);
        when(clientFactory.withBearerToken(baseClient, "github-token")).thenReturn(guardedClient);

        HttpGraphQlClient result = provider.forScope(42L);

        assertThat(result).isSameAs(guardedClient);
        verify(clientFactory).withBearerToken(baseClient, "github-token");
    }

    @Nested
    class RateLimitDelegation {

        @Test
        void trackRateLimitDelegates() {
            ClientGraphQlResponse response = Mockito.mock(ClientGraphQlResponse.class);
            GHRateLimit rateLimit = new GHRateLimit();
            when(rateLimitTracker.updateFromResponse(eq(42L), eq(response))).thenReturn(rateLimit);

            GHRateLimit result = provider.trackRateLimit(42L, response);

            assertThat(result).isSameAs(rateLimit);
            verify(rateLimitTracker).updateFromResponse(42L, response);
        }

        @Test
        void isRateLimitCriticalDelegates() {
            when(rateLimitTracker.isCritical(42L)).thenReturn(true);

            assertThat(provider.isRateLimitCritical(42L)).isTrue();
            verify(rateLimitTracker).isCritical(42L);
        }

        @Test
        void waitIfRateLimitLowDelegates() throws InterruptedException {
            when(rateLimitTracker.waitIfNeeded(42L)).thenReturn(true);

            boolean result = provider.waitIfRateLimitLow(42L);

            assertThat(result).isTrue();
            verify(rateLimitTracker).waitIfNeeded(42L);
        }

        @Test
        void getRateLimitRemainingDelegates() {
            when(rateLimitTracker.getRemaining(42L)).thenReturn(500);

            assertThat(provider.getRateLimitRemaining(42L)).isEqualTo(500);
            verify(rateLimitTracker).getRemaining(42L);
        }

        @Test
        void getRateLimitResetAtDelegates() {
            Instant resetAt = Instant.parse("2026-01-15T10:30:00Z");
            when(rateLimitTracker.getResetAt(42L)).thenReturn(resetAt);

            assertThat(provider.getRateLimitResetAt(42L)).isEqualTo(resetAt);
            verify(rateLimitTracker).getResetAt(42L);
        }

        @Test
        void getRateLimitTrackerReturnsInstance() {
            assertThat(provider.getRateLimitTracker()).isSameAs(rateLimitTracker);
        }
    }

    @Nested
    class CircuitBreakerTests {

        @Test
        void isCircuitClosedWhenClosed() {
            when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.CLOSED);

            assertThat(provider.isCircuitClosed()).isTrue();
        }
    }
}
