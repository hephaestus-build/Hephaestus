package de.tum.cit.aet.hephaestus.core.provenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestSystemSecrets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The bootstrap against PostgreSQL. The test cleanup keeps {@code provenance_signing_key}, so each case leaves a key
 * that the bootstrap made, as every other test of this context expects.
 */
class ProvenanceKeysIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ProvenanceKeys keys;

    @Autowired
    private ProvenanceSigningKeyRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldMakeExactlyOneKeyWhenTwoServersBootstrapAtOnce() throws Exception {
        jdbc.update("DELETE FROM provenance_signing_key");
        var start = new CountDownLatch(1);
        ExecutorService servers = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<Void>> bootstraps = List.of(
                    CompletableFuture.runAsync(() -> awaitThenBootstrap(start), servers),
                    CompletableFuture.runAsync(() -> awaitThenBootstrap(start), servers));
            start.countDown();
            CompletableFuture.allOf(bootstraps.toArray(CompletableFuture[]::new))
                    .get(30, TimeUnit.SECONDS);
        } finally {
            servers.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM provenance_signing_key", Long.class))
                .isEqualTo(1L);
        // The key opens with the system key, so the start-up check passes on it.
        keys.verifyAllOpen();
    }

    @Test
    void shouldStopTheStartUpCheckWhenTheSystemKeyDoesNotOpenAStoredKey() {
        keys.ensureSigningKey();
        String kid = repository
                .findFirstByOrderByCreatedAtDescKidDesc()
                .orElseThrow()
                .getKid();
        SystemSecrets another = TestSystemSecrets.systemSecrets("another-encryption-key-32-bytes!");
        var withAnotherKey = new ProvenanceKeys(repository, another, Clock.systemUTC());

        assertThatThrownBy(withAnotherKey::verifyAllOpen)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(kid)
                .hasMessageContaining("does not open the stored provenance signing key");
    }

    @Test
    void shouldPublishThePublicKeysToAReaderWhoIsNotSignedIn() {
        keys.ensureSigningKey();
        String kid = repository
                .findFirstByOrderByCreatedAtDescKidDesc()
                .orElseThrow()
                .getKid();

        webTestClient
                .get()
                .uri("/.well-known/hephaestus-provenance-keys.json")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.keys[?(@.kid == '" + kid + "')].crv")
                .isEqualTo(List.of("Ed25519"))
                .jsonPath("$.keys[*].d")
                .doesNotExist();
    }

    private void awaitThenBootstrap(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        keys.ensureSigningKey();
    }
}
