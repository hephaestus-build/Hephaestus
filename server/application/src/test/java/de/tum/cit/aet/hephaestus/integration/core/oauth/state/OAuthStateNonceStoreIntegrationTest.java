package de.tum.cit.aet.hephaestus.integration.core.oauth.state;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Single-use proof for the OAuth state nonce against a REAL Postgres. {@link OAuthStateNonceStoreTest}
 * mocks the repository, so it can only assert the store maps row-count→boolean — it cannot prove the
 * {@code UPDATE ... WHERE consumed_at IS NULL} conditional actually claims a nonce at-most-once. That
 * atomic claim is the replay defence: with a captured state token inside the HMAC TTL, an attacker
 * still gets exactly one callback because the second consume loses the row race.
 *
 * <p>This drives the real {@link OAuthStateNonceStore} (real {@code @Transactional} + JPQL) and, in
 * the concurrent case, two simultaneous transactions contending for the same nonce row — Postgres
 * serializes the UPDATE on the row lock so exactly one sees {@code rowcount == 1}.
 */
class OAuthStateNonceStoreIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OAuthStateNonceStore store;

    @Test
    void aNonceCanBeConsumedExactlyOnce() {
        String nonce = "seq-" + Long.toHexString(System.nanoTime()); // <= 32 chars (column limit)
        var binding = new OAuthStateService.StateBinding(
                1, IntegrationKind.GITHUB, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        store.issue(nonce, 1L, IntegrationKind.GITHUB, binding.issuedAt());

        assertThat(store.tryConsume(nonce, binding)).as("first consume wins").isTrue();
        assertThat(store.tryConsume(nonce, binding))
                .as("a replay of the same nonce is rejected")
                .isFalse();
    }

    @Test
    void mismatchedSignedContextDoesNotConsumeNonce() {
        String nonce = "bound-" + Long.toHexString(System.nanoTime());
        Instant issued = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        store.issue(nonce, 1L, IntegrationKind.GITHUB, issued);
        assertThat(store.tryConsume(nonce, new OAuthStateService.StateBinding(2, IntegrationKind.GITHUB, issued)))
                .isFalse();
        assertThat(store.tryConsume(nonce, new OAuthStateService.StateBinding(1, IntegrationKind.SLACK, issued)))
                .isFalse();
        assertThat(store.tryConsume(
                        nonce, new OAuthStateService.StateBinding(1, IntegrationKind.GITHUB, issued.plusSeconds(1))))
                .isFalse();
        assertThat(store.tryConsume(nonce, new OAuthStateService.StateBinding(1, IntegrationKind.GITHUB, issued, 42L)))
                .isFalse();
        assertThat(store.tryConsume(nonce, new OAuthStateService.StateBinding(1, IntegrationKind.GITHUB, issued)))
                .isTrue();
    }

    @Test
    void consumingAnUnknownNonceFailsClosed() {
        // No backing row (e.g. a forged state whose HMAC somehow validated) → reject, never accept.
        assertThat(store.tryConsume(
                        "absent-" + Long.toHexString(System.nanoTime()),
                        new OAuthStateService.StateBinding(1, IntegrationKind.GITHUB, Instant.now())))
                .isFalse();
    }

    @Test
    void concurrentConsumesOfOneNonceProduceExactlyOneWinner() throws Exception {
        String nonce = "race-" + Long.toHexString(System.nanoTime()); // <= 32 chars (column limit)
        var binding = new OAuthStateService.StateBinding(
                1, IntegrationKind.GITHUB, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        store.issue(nonce, 1L, IntegrationKind.GITHUB, binding.issuedAt());

        // Two OAuth callbacks land at once (a vendor retrying the redirect). The atomic conditional
        // UPDATE must let only ONE flip unconsumed→consumed; without the consumed_at IS NULL guard
        // both would "succeed" and an attacker's replay would be honoured.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<Boolean> first = pool.submit(consume(nonce, binding, ready, go));
            Future<Boolean> second = pool.submit(consume(nonce, binding, ready, go));
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();

            boolean r1 = first.get(15, TimeUnit.SECONDS);
            boolean r2 = second.get(15, TimeUnit.SECONDS);
            assertThat(List.of(r1, r2))
                    .as("exactly one concurrent consume wins")
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Boolean> consume(
            String nonce, OAuthStateService.StateBinding binding, CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            return store.tryConsume(nonce, binding);
        };
    }
}
