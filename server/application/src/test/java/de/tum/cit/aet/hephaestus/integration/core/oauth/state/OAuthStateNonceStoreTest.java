package de.tum.cit.aet.hephaestus.integration.core.oauth.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

/**
 * Unit tests for {@link OAuthStateNonceStore}'s service-layer plumbing: input validation (blank
 * rejection on {@code issue}/{@code tryConsume}) and skip-on-collision at issue time. The load-bearing
 * atomicity it delegates to {@code OAuthStateNonceRepository.markConsumed} — and the consume-once /
 * concurrency contract — is proven against real SQL by {@code OAuthStateNonceStoreIntegrationTest}.
 */
class OAuthStateNonceStoreTest extends BaseUnitTest {

    @Mock
    private OAuthStateNonceRepository repository;

    @InjectMocks
    private OAuthStateNonceStore store;

    @Test
    void issuePersistsRow() {
        when(repository.existsById("abc")).thenReturn(false);

        store.issue("abc", 7L, IntegrationKind.GITHUB, Instant.parse("2025-01-01T00:00:00Z"));

        verify(repository).save(any(OAuthStateNonce.class));
    }

    @Test
    void issueRejectsCollision() {
        when(repository.existsById("abc")).thenReturn(true);

        assertThatThrownBy(() -> store.issue("abc", 7L, IntegrationKind.GITHUB, Instant.parse("2025-01-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class);

        verify(repository, never()).save(any(OAuthStateNonce.class));
    }

    @Test
    void issueRejectsBlank() {
        assertThatThrownBy(() -> store.issue(null, 1L, IntegrationKind.GITHUB, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.issue("", 1L, IntegrationKind.GITHUB, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tryConsumeRejectsBlank() {
        assertThat(store.tryConsume(null, new OAuthStateService.StateBinding(1, IntegrationKind.GITHUB, Instant.now())))
                .isFalse();
        assertThat(store.tryConsume("", new OAuthStateService.StateBinding(1, IntegrationKind.GITHUB, Instant.now())))
                .isFalse();
        verify(repository, never())
                .markConsumed(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
    }
    // The full consume-once-then-reject sequence (and its concurrency) is proven against real SQL by
    // OAuthStateNonceStoreIntegrationTest; a mock "thin mapper" replay of it here added no signal.
}
