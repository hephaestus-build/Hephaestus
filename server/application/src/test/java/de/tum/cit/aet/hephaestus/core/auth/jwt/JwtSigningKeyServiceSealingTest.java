package de.tum.cit.aet.hephaestus.core.auth.jwt;

import static de.tum.cit.aet.hephaestus.testconfig.TestSystemEncryptionKeys.systemKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

/**
 * Proves a freshly generated signing key survives the full seal-on-write / unseal-on-load round-trip
 * through the service (yielding a usable ES256 signing JWK), and that the public JWK set never leaks
 * the private key. The repository is mocked as an in-memory holder so the test exercises the real
 * seal/unseal seam without a database — the sealer is a real {@link JwtSigningKeySealer} (32-char key).
 */
class JwtSigningKeyServiceSealingTest extends BaseUnitTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef"; // exactly 32 chars

    private static MockEnvironment env(String profile) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        return env;
    }

    private JwtSigningKeyService serviceWith(JwtSigningKeyRepository repo, JwtSigningKeySealer sealer) {
        return new JwtSigningKeyService(repo, env("dev"), sealer);
    }

    @Test
    void generatedSealedKey_roundTripsThroughDbLoad() {
        JwtSigningKeySealer sealer = new JwtSigningKeySealer(systemKey(KEY, "dev"));
        assertThat(sealer.isEnabled()).isTrue();

        JwtSigningKeyRepository repo = mock(JwtSigningKeyRepository.class);
        when(repo.countByActiveTrue()).thenReturn(0L);
        ArgumentCaptor<JwtSigningKey> saved = ArgumentCaptor.forClass(JwtSigningKey.class);
        when(repo.save(saved.capture())).thenAnswer(inv -> inv.getArgument(0));

        JwtSigningKeyService service = serviceWith(repo, sealer);
        service.ensureActiveKey();

        JwtSigningKey row = saved.getValue();
        // Persisted blob must be SEALED, not raw DER, and tagged accordingly.
        assertThat(row.getEncryptionKeyId()).isEqualTo(JwtSigningKeySealer.KEY_ID);
        assertThat(row.getPrivateKeyPem()[0]).isEqualTo(JwtSigningKeySealer.FORMAT_VERSION_V1);

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
    void disabledSealer_storesUnsealedAndStillLoads() {
        JwtSigningKeySealer sealer = new JwtSigningKeySealer(systemKey(null, "dev")); // disabled
        assertThat(sealer.isEnabled()).isFalse();

        JwtSigningKeyRepository repo = mock(JwtSigningKeyRepository.class);
        when(repo.countByActiveTrue()).thenReturn(0L);
        ArgumentCaptor<JwtSigningKey> saved = ArgumentCaptor.forClass(JwtSigningKey.class);
        when(repo.save(saved.capture())).thenAnswer(inv -> inv.getArgument(0));

        JwtSigningKeyService service = serviceWith(repo, sealer);
        service.ensureActiveKey();

        JwtSigningKey row = saved.getValue();
        assertThat(row.getEncryptionKeyId()).isEqualTo("v0-unsealed");

        when(repo.findActive()).thenReturn(List.of(row));
        assertThat(((ECKey) service.currentSigningKey()).isPrivate()).isTrue();
    }

    @Test
    void prod_existingUnsealedRow_failsClosed() {
        // A legacy v0-unsealed row must fail closed at both the startup assertion and the signing path.
        // ensureActiveKey() is intentionally NOT the guard here: it runs inside AuthJwtConfig's
        // swallowing @PostConstruct, so making it the guard would be inert — the exact bug this pins.
        JwtSigningKeySealer sealer = new JwtSigningKeySealer(systemKey(KEY, "prod"));
        JwtSigningKeyRepository repo = mock(JwtSigningKeyRepository.class);

        JwtSigningKey legacy = new JwtSigningKey();
        legacy.setKid("legacy-kid");
        legacy.setEncryptionKeyId("v0-unsealed");
        when(repo.findActive()).thenReturn(List.of(legacy));

        JwtSigningKeyService service = new JwtSigningKeyService(repo, env("prod"), sealer);

        // Startup assertion (called from AuthJwtConfig OUTSIDE the swallow) aborts boot.
        Assertions.assertThatThrownBy(service::assertProdKeysSealed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsealed");
        // Signing path also refuses to materialize the unsealed key.
        Assertions.assertThatThrownBy(service::currentSigningKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsealed");
    }

    @Test
    void coldBoot_acquiresAdvisoryLockBeforeInsertingAndRechecksUnderIt() {
        // The double-mint guard is ordering: read (fast path) → take the cluster-wide advisory lock →
        // RE-read under the lock → only then insert. Pin that order so a refactor cannot move the
        // insert outside the lock or drop the re-check (the load-bearing step countByActiveTrue's
        // Javadoc calls out). Postgres owns the cross-pod semantics of pg_advisory_xact_lock; this pins
        // OUR sequence around it.
        JwtSigningKeySealer sealer = new JwtSigningKeySealer(systemKey(KEY, "dev"));
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
        JwtSigningKeySealer sealer = new JwtSigningKeySealer(systemKey(KEY, "dev"));
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
        JwtSigningKeySealer sealer = new JwtSigningKeySealer(systemKey(KEY, "dev"));
        JwtSigningKeyRepository repo = mock(JwtSigningKeyRepository.class);
        when(repo.countByActiveTrue()).thenReturn(1L);

        serviceWith(repo, sealer).ensureActiveKey();

        verify(repo, never()).acquireBootstrapLock();
        verify(repo, never()).save(any());
    }

    @Test
    void prod_emptyTable_bootstrapsSealedKey() {
        JwtSigningKeySealer sealer = new JwtSigningKeySealer(systemKey(KEY, "prod"));
        JwtSigningKeyRepository repo = mock(JwtSigningKeyRepository.class);
        when(repo.countByActiveTrue()).thenReturn(0L);
        ArgumentCaptor<JwtSigningKey> saved = ArgumentCaptor.forClass(JwtSigningKey.class);
        when(repo.save(saved.capture())).thenAnswer(inv -> inv.getArgument(0));

        JwtSigningKeyService service = new JwtSigningKeyService(repo, env("prod"), sealer);

        // Prod + sealer enabled = OK to auto-generate a SEALED key (this is the fix that lets prod boot).
        service.ensureActiveKey();

        assertThat(saved.getValue().getEncryptionKeyId()).isEqualTo(JwtSigningKeySealer.KEY_ID);
    }
}
