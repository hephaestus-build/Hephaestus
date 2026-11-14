package de.tum.cit.aet.hephaestus.core.auth.jwt;

import static de.tum.cit.aet.hephaestus.testconfig.TestSystemSecrets.systemSecrets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Proves a freshly generated signing key survives the full seal-on-write / unseal-on-load round-trip
 * through the service (yielding a usable ES256 signing JWK), and that the public JWK set never leaks
 * the private key. The repository is mocked as an in-memory holder so the test exercises the real
 * seal/unseal seam without a database — sealing is a real {@link SystemSecrets} (32-char key).
 */
class JwtSigningKeyServiceSealingTest extends BaseUnitTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef"; // exactly 32 chars

    private JwtSigningKeyService serviceWith(JwtSigningKeyRepository repo, SystemSecrets secrets) {
        return new JwtSigningKeyService(repo, secrets, AuthPropertiesFixture.defaults(), Clock.systemUTC());
    }

    @Test
    void generatedSealedKey_roundTripsThroughDbLoad() {
        SystemSecrets sealer = systemSecrets(KEY, "dev");
        assertThat(sealer.isEnabled()).isTrue();

        JwtSigningKeyRepository repo = mock(JwtSigningKeyRepository.class);
        when(repo.countByActiveTrue()).thenReturn(0L);
        ArgumentCaptor<JwtSigningKey> saved = ArgumentCaptor.forClass(JwtSigningKey.class);
        when(repo.save(saved.capture())).thenAnswer(inv -> inv.getArgument(0));

        JwtSigningKeyService service = serviceWith(repo, sealer);
        service.ensureActiveKey();

        JwtSigningKey row = saved.getValue();
        // The persisted value is sealed under the active key, not raw DER.
        assertThat(row.resealWithActiveKey(sealer)).isFalse();

        // Feed the sealed row back as the active set; the read path must unseal and produce a
        // private-key-bearing ES256 JWK usable for signing.
        when(repo.findActive()).thenReturn(List.of(row));
        JWK current = service.currentSigningKey();

        assertThat(current).isInstanceOf(ECKey.class);
        ECKey ec = (ECKey) current;
        assertThat(ec.isPrivate()).isTrue();
        assertThat(ec.getKeyID()).isEqualTo(row.getKid());

        // Public set must NOT carry the private key.
        JWKSet publicSet = service.publicJwkSet();
        assertThat(publicSet.getKeys()).hasSize(1);
        assertThat(((ECKey) publicSet.getKeys().get(0)).isPrivate()).isFalse();
    }

    @Test
    void shouldRefuseToSignWithAKeySealedForAnotherRow() {
        SystemSecrets secrets = systemSecrets(KEY, "dev");
        JwtSigningKeyRepository repo = mock(JwtSigningKeyRepository.class);
        ArgumentCaptor<JwtSigningKey> saved = ArgumentCaptor.forClass(JwtSigningKey.class);
        when(repo.save(saved.capture())).thenAnswer(inv -> inv.getArgument(0));
        serviceWith(repo, secrets).ensureActiveKey();
        JwtSigningKey row = saved.getValue();
        row.setKid("another-kid");
        when(repo.findActive()).thenReturn(List.of(row));

        assertThatThrownBy(() -> serviceWith(repo, secrets).currentSigningKey())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another-kid");
    }

    @Test
    void shouldRefuseToCreateAnUnsealedKeyWithoutAnEncryptionKey() {
        var sealer = systemSecrets(null, "dev");
        var repo = mock(JwtSigningKeyRepository.class);
        assertThatThrownBy(() -> serviceWith(repo, sealer).ensureActiveKey())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sealing is disabled");
        verify(repo, never()).save(any());
    }

    @Test
    void coldBoot_acquiresAdvisoryLockBeforeInsertingAndRechecksUnderIt() {
        // The double-mint guard is ordering: read (fast path) → take the cluster-wide advisory lock →
        // RE-read under the lock → only then insert. Pin that order so a refactor cannot move the
        // insert outside the lock or drop the re-check (the load-bearing step countByActiveTrue's
        // Javadoc calls out). Postgres owns the cross-pod semantics of pg_advisory_xact_lock; this pins
        // OUR sequence around it.
        SystemSecrets sealer = systemSecrets(KEY, "dev");
        JwtSigningKeyRepository repo = mock(JwtSigningKeyRepository.class);
        when(repo.countByActiveTrue()).thenReturn(0L); // empty before AND after the lock
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        serviceWith(repo, sealer).ensureActiveKey();

        var ordered = inOrder(repo);
        ordered.verify(repo).countByActiveTrue(); // fast-path read
        ordered.verify(repo).acquireBootstrapLock(); // serialize the bootstrap
        ordered.verify(repo).countByActiveTrue(); // re-check UNDER the lock
        ordered.verify(repo).save(any()); // insert exactly once
    }

    @Test
    void coldBoot_whenAnotherPodWonDuringLockWait_doesNotDoubleMint() {
        // Two pods race past the unlocked fast path (both see 0). The loser blocks on the advisory lock;
        // by the time it acquires it, the winner has committed a key. The post-lock re-check must see
        // that key and SKIP the insert — otherwise the cluster mints two active signing identities.
        SystemSecrets sealer = systemSecrets(KEY, "dev");
        JwtSigningKeyRepository repo = mock(JwtSigningKeyRepository.class);
        // 0 at the fast path, then 1 after acquiring the lock (the winner inserted while we waited).
        when(repo.countByActiveTrue()).thenReturn(0L).thenReturn(1L);

        serviceWith(repo, sealer).ensureActiveKey();

        verify(repo).acquireBootstrapLock();
        verify(repo, never()).save(any()); // no second key is minted
    }

    @Test
    void warmBoot_skipsTheAdvisoryLockEntirely() {
        // The overwhelmingly common path: a key already exists, so the cheap unlocked read short-circuits
        // and the bootstrap lock is never taken (it would needlessly serialize every warm boot).
        SystemSecrets sealer = systemSecrets(KEY, "dev");
        JwtSigningKeyRepository repo = mock(JwtSigningKeyRepository.class);
        when(repo.countByActiveTrue()).thenReturn(1L);

        serviceWith(repo, sealer).ensureActiveKey();

        verify(repo, never()).acquireBootstrapLock();
        verify(repo, never()).save(any());
    }

    @Test
    void prod_emptyTable_bootstrapsSealedKey() {
        SystemSecrets sealer = systemSecrets(KEY, "prod");
        JwtSigningKeyRepository repo = mock(JwtSigningKeyRepository.class);
        when(repo.countByActiveTrue()).thenReturn(0L);
        ArgumentCaptor<JwtSigningKey> saved = ArgumentCaptor.forClass(JwtSigningKey.class);
        when(repo.save(saved.capture())).thenAnswer(inv -> inv.getArgument(0));

        JwtSigningKeyService service = serviceWith(repo, sealer);

        // Sealer enabled = OK to auto-generate a SEALED key (this is the fix that lets prod boot).
        service.ensureActiveKey();

        assertThat(saved.getValue().resealWithActiveKey(sealer)).isFalse();
    }
}
