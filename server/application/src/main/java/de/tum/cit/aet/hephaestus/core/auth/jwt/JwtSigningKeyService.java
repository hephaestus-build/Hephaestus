package de.tum.cit.aet.hephaestus.core.auth.jwt;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB-backed JWK source for Hephaestus-issued JWTs. Acts as a Spring Security
 * {@link JWKSource} (consumed by {@code NimbusJwtEncoder} and {@code NimbusJwtDecoder}).
 *
 * <h2>Bootstrapping</h2>
 * On first run (empty {@code jwt_signing_key} table), {@link #ensureActiveKey()} generates
 * a P-256 EC key pair. The private PKCS#8 DER bytes are sealed at rest by
 * {@link JwtSigningKeySealer} (AES-256-GCM under the system master key) and stored tagged
 * {@code encryption_key_id = JwtSigningKeySealer.KEY_ID}. The database migration seals legacy
 * private keys before runtime starts. Startup and key materialization refuse unsealed rows.
 *
 * <h2>Key lifecycle</h2>
 * A single active key signs all tokens; re-keying today means deploying with a fresh key
 * (the old row is left {@code active=true} and still verifies until manually retired). Zero-downtime,
 * NATS-coordinated rotation across pods is not yet implemented — see ADR 0017 for the planned design.
 *
 * <h2>Hot-path</h2>
 * {@link #get(JWKSelector, SecurityContext)} is called on every JWT
 * encode/decode. We resolve from an {@link AtomicReference}-cached {@link JWKSet}, refreshed from DB
 * on TTL expiry ({@value #CACHE_TTL_MILLIS} ms).
 */
@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("JWT signing keys are system-wide; get() returns the global JWK set, not tenant data")
public class JwtSigningKeyService implements JWKSource<SecurityContext> {

    private static final Logger log = LoggerFactory.getLogger(JwtSigningKeyService.class);

    private final JwtSigningKeyRepository repository;
    private final Environment environment;
    private final JwtSigningKeySealer sealer;
    private final AtomicReference<@Nullable CachedSet> cache = new AtomicReference<>();

    /** How long to trust the in-memory key cache before reloading from DB. */
    private static final long CACHE_TTL_MILLIS = 60_000L;

    public JwtSigningKeyService(
            JwtSigningKeyRepository repository, Environment environment, JwtSigningKeySealer sealer) {
        this.repository = repository;
        this.environment = environment;
        this.sealer = sealer;
    }

    /**
     * Ensure at least one active key exists. Called from the security configuration's
     * {@code @PostConstruct} so dev/CI boots without a manual bootstrap step. Production
     * deploys can pre-seed via Liquibase customChange if they need deterministic kids.
     */
    @Transactional
    public void ensureActiveKey() {
        // Fast path: a cheap unlocked read avoids taking the advisory lock on the overwhelmingly common
        // warm-boot case (key already present). Pure optimisation — the post-lock re-check below is what
        // actually guarantees a single insert.
        if (repository.countByActiveTrue() > 0) {
            return;
        }
        // Cold boot: serialize the bootstrap cluster-wide. pg_advisory_xact_lock blocks until this tx
        // holds (17,1), then auto-releases on commit/rollback. A second pod that raced past the fast-path
        // read above blocks here, then sees the now-committed key in the re-check and no-ops — so exactly
        // one active signing identity is ever created. (Replaces the JVM-local `synchronized`, which could
        // not coordinate across pods.)
        repository.acquireBootstrapLock();
        if (repository.countByActiveTrue() > 0) {
            return;
        }
        if (!sealer.isEnabled()) {
            throw new IllegalStateException(
                    "No JWT signing key exists and sealing is disabled. The server does not create an unsealed "
                            + "signing key. Set hephaestus.security.encryption-key (ADR 0017).");
        }
        JwtSigningKey row = generateNewKeyRow();
        repository.save(row);
        cache.set(null);
        log.info("auth.jwt: bootstrapped initial signing key kid={} sealed={}", row.getKid(), sealer.isEnabled());
    }

    /** Refuses an active legacy key that the database migration has not sealed. */
    public void assertKeysSealed() {
        // Build-only profiles load the authentication contract without a database.
        if (!environment.matchesProfiles("specs", "cds-training") && hasUnsealedActiveKey()) {
            throw new IllegalStateException("Active JWT signing key is unsealed. Run the database migration first.");
        }
    }

    private boolean hasUnsealedActiveKey() {
        return repository.findActive().stream()
                .anyMatch(k -> !JwtSigningKeySealer.KEY_ID.equals(k.getEncryptionKeyId()));
    }

    private JwtSigningKey generateNewKeyRow() {
        try {
            ECKey jwk = new ECKeyGenerator(Curve.P_256)
                    .keyUse(KeyUse.SIGNATURE)
                    .keyID(UUID.randomUUID().toString())
                    .generate();
            JwtSigningKey row = new JwtSigningKey();
            row.setKid(jwk.getKeyID());
            row.setAlgorithm("ES256");
            row.setPublicKeyPem(toPem("PUBLIC KEY", jwk.toECPublicKey().getEncoded()));
            byte[] privateDer = jwk.toECPrivateKey().getEncoded();
            row.setPrivateKeyPem(sealer.seal(privateDer));
            row.setEncryptionKeyId(sealer.keyId());
            row.setActive(true);
            return row;
        } catch (Exception e) {
            throw new IllegalStateException("failed to generate ES256 signing key", e);
        }
    }

    /** Used by both {@code NimbusJwtEncoder} (signing) and {@code NimbusJwtDecoder} (verification). */
    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) {
        return loadJwkSet().getKeys().stream()
                .filter(selector.getMatcher()::matches)
                .toList();
    }

    /**
     * Return the freshest active key as a {@link JWK} — the encoder uses this directly to
     * sign new tokens.
     */
    public JWK currentSigningKey() {
        List<JWK> keys = loadJwkSet().getKeys();
        if (keys.isEmpty()) {
            throw new IllegalStateException(
                    "There is no active JWT signing key. ensureActiveKey() must run before issuance.");
        }
        return keys.get(0); // ordering enforced by repository.findActive()
    }

    /** Exposed for the {@code /.well-known/jwks.json} endpoint. Public keys only. */
    public JWKSet publicJwkSet() {
        return new JWKSet(loadJwkSet().getKeys().stream().map(JWK::toPublicJWK).toList());
    }

    private JWKSet loadJwkSet() {
        CachedSet snapshot = cache.get();
        long now = System.currentTimeMillis();
        if (snapshot != null && (now - snapshot.loadedAt) < CACHE_TTL_MILLIS) {
            return snapshot.set;
        }
        JWKSet set = reload();
        cache.set(new CachedSet(set, now));
        return set;
    }

    private JWKSet reload() {
        List<JWK> jwks = repository.findActive().stream().map(this::toJwk).toList();
        return new JWKSet(jwks);
    }

    private JWK toJwk(JwtSigningKey row) {
        if (!JwtSigningKeySealer.KEY_ID.equals(row.getEncryptionKeyId())) {
            throw new IllegalStateException("Refusing to use an unsealed or unsupported JWT signing key.");
        }
        try {
            byte[] publicDer = stripPem(row.getPublicKeyPem());
            ECPublicKey publicKey =
                    (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicDer));
            byte[] privateDer = sealer.unseal(row.getPrivateKeyPem());
            ECPrivateKey privateKey =
                    (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(privateDer));
            return new ECKey.Builder(Curve.P_256, publicKey)
                    .privateKey(privateKey)
                    .keyID(row.getKid())
                    .keyUse(KeyUse.SIGNATURE)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("failed to materialize JWK kid=" + row.getKid(), e);
        }
    }

    private static String toPem(String label, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + b64 + "\n-----END " + label + "-----\n";
    }

    private static byte[] stripPem(String pem) {
        String body = pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(body);
    }

    private record CachedSet(JWKSet set, long loadedAt) {}
}
