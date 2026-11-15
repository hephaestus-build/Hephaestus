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
import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.EncryptionException;
import de.tum.cit.aet.hephaestus.core.security.SystemSecretOwner;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB-backed JWK source for Hephaestus-issued JWTs. Acts as a Spring Security
 * {@link JWKSource} (consumed by {@code NimbusJwtEncoder} and {@code NimbusJwtDecoder}).
 *
 * <h2>Bootstrapping</h2>
 * On first run (empty {@code jwt_signing_key} table), {@link #ensureActiveKey()} generates
 * a P-256 EC key pair. {@link SystemSecrets} seals the private PKCS#8 DER bytes at rest, bound to the row.
 *
 * <h2>Key lifecycle</h2>
 * The newest active key signs; every active key verifies. {@code hephaestus.auth.signing-key-not-before} makes a new
 * key at server start. {@link #retireSupersededKeys()} retires an older key once each token it signed has expired.
 * A token that names a key this server has not loaded yet reloads the set once, so a new key from another replica
 * verifies at once (ADR 0104).
 *
 * <h2>Hot-path</h2>
 * {@link #get(JWKSelector, SecurityContext)} is called on every JWT
 * encode/decode. We resolve from an {@link AtomicReference}-cached {@link JWKSet}, refreshed from DB
 * on TTL expiry ({@value #CACHE_TTL_MILLIS} ms).
 */
@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("JWT signing keys are system-wide; get() returns the global JWK set, not tenant data")
public class JwtSigningKeyService implements JWKSource<SecurityContext>, SystemSecretOwner {

    private static final Logger log = LoggerFactory.getLogger(JwtSigningKeyService.class);

    private final JwtSigningKeyRepository repository;
    private final SystemSecrets secrets;
    private final AuthProperties properties;
    private final Clock clock;
    private final AtomicReference<@Nullable CachedSet> cache = new AtomicReference<>();

    /** How long to trust the in-memory key cache before reloading from DB. */
    private static final long CACHE_TTL_MILLIS = 60_000L;

    /** The shortest time between two reloads that a token from an unknown key causes. */
    private static final long UNKNOWN_KEY_RELOAD_MILLIS = 5_000L;

    public JwtSigningKeyService(
            JwtSigningKeyRepository repository, SystemSecrets secrets, AuthProperties properties, Clock clock) {
        this.repository = repository;
        this.secrets = secrets;
        this.properties = properties;
        this.clock = clock;
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
        Instant notBefore = requestedNotBefore();
        if (hasCurrentKey(notBefore)) {
            return;
        }
        // Cold boot: serialize the bootstrap cluster-wide. pg_advisory_xact_lock waits for this transaction to hold
        // (17,1) and releases it on commit or rollback. A second pod that raced past the fast-path read above waits
        // here, then sees the committed key in the re-check and does nothing, so one rotation creates one key.
        repository.acquireBootstrapLock();
        if (hasCurrentKey(notBefore)) {
            return;
        }
        if (!secrets.isEnabled()) {
            throw new IllegalStateException(
                    "No JWT signing key exists and sealing is disabled. The server does not create an unsealed "
                            + "signing key. Set hephaestus.security.encryption-key (ADR 0017).");
        }
        JwtSigningKey row = generateNewKeyRow();
        repository.save(row);
        cache.set(null);
        log.info("auth.jwt: created signing key kid={}, rotation={}", row.getKid(), notBefore != null);
    }

    /**
     * Opens every active signing key. A server whose system key differs from the key that sealed them would start, report
     * ready and refuse every session; this stops it at start with the identifiers of the keys involved instead.
     */
    @Transactional(readOnly = true)
    public void verifyActiveKeysOpen() {
        // No guard for a missing system key, unlike StoredSystemSecrets#verifyAllOpen: a server that cannot open its
        // signing keys cannot sign or verify a session, so it stops even in development.
        for (JwtSigningKey row : repository.findActive()) {
            try {
                row.privateKey(secrets);
            } catch (EncryptionException mismatch) {
                throw new IllegalStateException(
                        "hephaestus.security.encryption-key does not open the stored JWT signing"
                                + " key " + row.getKid() + ": " + row.sealingMismatch(secrets)
                                + ". Restore the system key that sealed it, as docs/admin/secret-custody.mdx describes.",
                        mismatch);
            }
        }
    }

    /** The configured instant, or null when it is unset or still in the future. */
    private @Nullable Instant requestedNotBefore() {
        Instant notBefore = properties.signingKeyNotBefore();
        if (notBefore == null) return null;
        if (notBefore.isAfter(clock.instant())) {
            log.warn("auth.jwt: ignored signing-key-not-before in the future: notBefore={}", notBefore);
            return null;
        }
        return notBefore;
    }

    private boolean hasCurrentKey(@Nullable Instant notBefore) {
        return notBefore == null
                ? repository.countByActiveTrue() > 0
                : repository.existsByActiveTrueAndCreatedAtGreaterThanEqual(notBefore);
    }

    /**
     * Retires each key that a newer key replaced before every token it signed expired: {@code access-ttl}, plus the
     * cache period in which another replica still signed with it.
     */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    @SchedulerLock(name = "jwt-signing-key-retirement", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    @Transactional
    public void retireSupersededKeys() {
        Instant cutoff = clock.instant().minus(properties.accessTtl()).minusMillis(2 * CACHE_TTL_MILLIS);
        int retired = repository.retireSupersededBefore(cutoff);
        if (retired > 0) {
            cache.set(null);
            log.info("auth.jwt: retired superseded signing keys: count={}", retired);
        }
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
            row.replacePrivateKey(jwk.toECPrivateKey().getEncoded(), secrets);
            row.setActive(true);
            return row;
        } catch (Exception e) {
            throw new IllegalStateException("failed to generate ES256 signing key", e);
        }
    }

    /** Used by both {@code NimbusJwtEncoder} (signing) and {@code NimbusJwtDecoder} (verification). */
    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) {
        List<JWK> matches = select(loadJwkSet(), selector);
        Set<String> keyIds = selector.getMatcher().getKeyIDs();
        if (matches.isEmpty() && keyIds != null && !keyIds.isEmpty()) {
            CachedSet snapshot = cache.get();
            long now = clock.millis();
            if (snapshot == null || now - snapshot.loadedAt >= UNKNOWN_KEY_RELOAD_MILLIS) {
                JWKSet reloaded = reload();
                cache.set(new CachedSet(reloaded, now));
                matches = select(reloaded, selector);
            }
        }
        return matches;
    }

    private static List<JWK> select(JWKSet set, JWKSelector selector) {
        return set.getKeys().stream().filter(selector.getMatcher()::matches).toList();
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
        long now = clock.millis();
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
        try {
            byte[] publicDer = stripPem(row.getPublicKeyPem());
            ECPublicKey publicKey =
                    (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicDer));
            byte[] privateDer = row.privateKey(secrets);
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

    @Override
    @Transactional
    public int resealWithActiveKey(SystemSecrets secrets) {
        int resealed = 0;
        for (var row : repository.lockAllForKeyRotation()) {
            if (row.resealWithActiveKey(secrets)) resealed++;
        }
        return resealed;
    }
}
