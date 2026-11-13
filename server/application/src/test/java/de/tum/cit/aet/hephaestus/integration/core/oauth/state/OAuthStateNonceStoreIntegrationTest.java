package de.tum.cit.aet.hephaestus.integration.core.oauth.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.oauth.OAuthCallbackService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

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

    private long fixtureWorkspaceId;

    @BeforeEach
    void ownWorkspace() {
        fixtureWorkspaceId = workspaces
                .save(WorkspaceTestFixtures.activeWorkspace(
                        "nonce-" + UUID.randomUUID().toString().substring(0, 8)))
                .getId();
    }

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private OAuthCallbackService callbacks;

    @Autowired
    private TransactionTemplate transactions;

    @ParameterizedTest
    @EnumSource(
            value = IntegrationKind.class,
            names = {"GITHUB", "SLACK"})
    void shouldRequireTheNewLifecycleBindingWhenStartingAgainAfterUninstall(IntegrationKind kind) {
        var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                "oauth-generation-" + UUID.randomUUID().toString().substring(0, 8)));
        String nonce = UUID.randomUUID().toString().replace("-", "");
        Instant issued = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        store.issue(nonce, workspace.getId(), kind, issued);
        var verified = new OAuthStateService.StateBinding(workspace.getId(), kind, issued);
        var original = store.boundAttempt(nonce, verified);
        transactions.executeWithoutResult(status -> connections
                .findByIdAndWorkspaceId(original.connectionId(), workspace.getId())
                .orElseThrow()
                .setState(IntegrationState.UNINSTALLED));
        String replacementNonce = UUID.randomUUID().toString().replace("-", "");
        store.issue(replacementNonce, workspace.getId(), kind, issued);
        var replacement = store.boundAttempt(replacementNonce, verified);
        assertThat(replacement.connectionId()).isEqualTo(original.connectionId());
        assertThat(replacement.lifecycleGeneration()).isNotEqualTo(original.lifecycleGeneration());
        assertThat(store.boundAttempt(nonce, verified)).isEqualTo(original);
        assertThatThrownBy(() -> callbacks.findAttemptConnection(original)).isInstanceOf(IllegalStateException.class);
        assertThat(callbacks.findAttemptConnection(replacement).getId()).isEqualTo(replacement.connectionId());
    }

    @ParameterizedTest
    @EnumSource(
            value = IntegrationKind.class,
            names = {"GITHUB", "SLACK"})
    void shouldRejectTheOldNonceEvenAfterTheSameConnectionResumes(IntegrationKind kind) {
        var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                "oauth-resume-" + UUID.randomUUID().toString().substring(0, 8)));
        String nonce = UUID.randomUUID().toString().replace("-", "");
        Instant issued = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        store.issue(nonce, workspace.getId(), kind, issued);
        var original = store.boundAttempt(nonce, new OAuthStateService.StateBinding(workspace.getId(), kind, issued));
        transactions.executeWithoutResult(status -> {
            var owner = connections
                    .findByIdAndWorkspaceId(original.connectionId(), workspace.getId())
                    .orElseThrow();
            owner.setState(IntegrationState.SUSPENDED);
            owner.setState(IntegrationState.ACTIVE);
        });
        assertThatThrownBy(() -> callbacks.findAttemptConnection(original)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aNonceCanBeConsumedExactlyOnce() {
        String nonce = "seq-" + Long.toHexString(System.nanoTime()); // <= 32 chars (column limit)
        var binding = new OAuthStateService.StateBinding(
                fixtureWorkspaceId, IntegrationKind.GITHUB, Instant.now().truncatedTo(ChronoUnit.SECONDS));
        store.issue(nonce, fixtureWorkspaceId, IntegrationKind.GITHUB, binding.issuedAt());

        assertThat(store.tryConsume(nonce, binding)).as("first consume wins").isTrue();
        assertThat(store.tryConsume(nonce, binding))
                .as("a replay of the same nonce is rejected")
                .isFalse();
    }

    @Test
    void mismatchedSignedContextDoesNotConsumeNonce() {
        String nonce = "bound-" + Long.toHexString(System.nanoTime());
        Instant issued = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        store.issue(nonce, fixtureWorkspaceId, IntegrationKind.GITHUB, issued);
        assertThat(store.tryConsume(
                        nonce,
                        new OAuthStateService.StateBinding(fixtureWorkspaceId + 1, IntegrationKind.GITHUB, issued)))
                .isFalse();
        assertThat(store.tryConsume(
                        nonce, new OAuthStateService.StateBinding(fixtureWorkspaceId, IntegrationKind.SLACK, issued)))
                .isFalse();
        assertThat(store.tryConsume(
                        nonce,
                        new OAuthStateService.StateBinding(
                                fixtureWorkspaceId, IntegrationKind.GITHUB, issued.plusSeconds(1))))
                .isFalse();
        assertThat(store.tryConsume(
                        nonce,
                        new OAuthStateService.StateBinding(fixtureWorkspaceId, IntegrationKind.GITHUB, issued, 42L)))
                .isFalse();
        assertThat(store.tryConsume(
                        nonce, new OAuthStateService.StateBinding(fixtureWorkspaceId, IntegrationKind.GITHUB, issued)))
                .isTrue();
    }

    @Test
    void consumingAnUnknownNonceFailsClosed() {
        // No backing row (e.g. a forged state whose HMAC somehow validated) → reject, never accept.
        assertThat(store.tryConsume(
                        "absent-" + Long.toHexString(System.nanoTime()),
                        new OAuthStateService.StateBinding(fixtureWorkspaceId, IntegrationKind.GITHUB, Instant.now())))
                .isFalse();
    }

    @Test
    void concurrentConsumesOfOneNonceProduceExactlyOneWinner() throws Exception {
        String nonce = "race-" + Long.toHexString(System.nanoTime()); // <= 32 chars (column limit)
        var binding = new OAuthStateService.StateBinding(
                fixtureWorkspaceId, IntegrationKind.GITHUB, Instant.now().truncatedTo(ChronoUnit.SECONDS));
        store.issue(nonce, fixtureWorkspaceId, IntegrationKind.GITHUB, binding.issuedAt());

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
