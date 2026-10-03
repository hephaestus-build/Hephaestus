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
import org.springframework.core.env.Profiles;
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
 * {@code encryption_key_id = JwtSigningKeySealer.KEY_ID} whenever the sealer is enabled —
 * i.e. always in prod, which requires {@code hephaestus.security.encryption-key}. When the
 * sealer is disabled (a local dev/CI/test boot with no key) the private bytes are stored as
 * raw PKCS#8 DER tagged {@code "v0-unsealed"}.
 *
 * <p>An <em>unsealed</em> private key in the DB is a forge-any-token risk, so the service
 * <strong>fails closed in the {@code prod} profile</strong> on a legacy/misconfigured
 * {@code v0-unsealed} row at two points: {@link #assertProdKeysSealed()} (run from a NON-swallowed
 * startup hook, so the boot aborts) and {@link #toJwk} (so even a deferred first-issuance can never
 * sign with an unsealed key). Prod auto-generation produces a <em>sealed</em> key (the sealer is
 * enabled there), so a fresh prod boots cleanly; only a pre-existing unsealed row trips the guard.
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

    private static final String UNSEALED_KEY_ID = "v0-unsealed";

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
        if (isProd() && !sealer.isEnabled()) {
            // Belt-and-suspenders: the sealer ctor already fails fast in prod when the key is missing.
            throw new IllegalStateException(
                    "No JWT signing key exists and sealing is disabled. The server does not create an unsealed "
                            + "signing key in prod. Set hephaestus.security.encryption-key (ADR 0017).");
        }
        JwtSigningKey row = generateNewKeyRow();
        repository.save(row);
        cache.set(null);
        log.info("auth.jwt: bootstrapped initial signing key kid={} sealed={}", row.getKid(), sealer.isEnabled());
    }

    /**
     * Fail closed in the {@code prod} profile if any active signing key is stored unsealed
     * ({@code encryption_key_id="v0-unsealed"}) — a DB read of such a row lets an attacker forge any
     * token. Invoked from a NON-swallowed startup hook (so the boot actually aborts, unlike the
     * best-effort {@link #ensureActiveKey()} bootstrap) and re-enforced at key materialization
     * ({@link #toJwk}) so a deferred first-issuance can never sign with an unsealed key either.
     * A freshly generated key in prod is always sealed, so this only trips on a legacy/misconfigured
     * row, which must be rotated out before deploying (ADR 0017 / auth-cutover runbook).
     */
    public void assertProdKeysSealed() {
        if (isProd() && hasUnsealedActiveKey()) {
            throw new IllegalStateException(
                    "Active JWT signing key is stored unsealed (encryption_key_id=" + UNSEALED_KEY_ID
                            + "). Refusing to operate in prod — a DB read would let an attacker forge any token. "
                            + "Rotate the legacy row out before deploying (ADR 0017 / auth-cutover runbook).");
        }
    }

    private boolean hasUnsealedActiveKey() {
        return repository.findActive().stream().anyMatch(k -> UNSEALED_KEY_ID.equals(k.getEncryptionKeyId()));
    }

    private boolean isProd() {
        return environment.acceptsProfiles(Profiles.of("prod"));
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
            if (sealer.isEnabled()) {
                // Seal the PKCS#8 DER under the system master key (AES-256-GCM). A DB read alone
                // can no longer forge tokens — the private bytes are useless without the key.
                row.setPrivateKeyPem(sealer.seal(privateDer));
                row.setEncryptionKeyId(sealer.keyId());
            } else {
                // Dev/CI/test with no key: store raw PKCS#8 DER. The tag lets a future migration
                // find rows to re-encrypt and lets ensureActiveKey() refuse these in prod.
                row.setPrivateKeyPem(privateDer);
                row.setEncryptionKeyId(UNSEALED_KEY_ID);
            }
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
            throw new IllegalStateException("no active JWT signing key — ensureActiveKey() must run before issuance");
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
        boolean unsealed = UNSEALED_KEY_ID.equals(row.getEncryptionKeyId());
        // Fail closed BEFORE the materialization try/catch so the reason is not masked: never use an
        // unsealed (forgeable) signing key in prod, even on the deferred first-issuance path.
        if (unsealed && isProd()) {
            throw new IllegalStateException(
                    "Refusing to use an unsealed JWT signing key (kid=" + row.getKid() + ") in prod.");
        }
        try {
            byte[] publicDer = stripPem(row.getPublicKeyPem());
            ECPublicKey publicKey =
                    (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicDer));
            // Unseal the private bytes if this row was sealed at rest; legacy "v0-unsealed"
            // rows carry raw PKCS#8 DER and parse directly.
            byte[] privateDer = unsealed ? row.getPrivateKeyPem() : sealer.unseal(row.getPrivateKeyPem());
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
