package de.tum.cit.aet.hephaestus.agent.attempt;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.JWTVerifier;
import de.tum.cit.aet.hephaestus.agent.config.AgentBindingLimits;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This server's signing key, and every server's public key. A token that one server signed verifies on any server, so
 * an attempt does not depend on the server that started it.
 *
 * <p>Verification reads memory: {@link #refresh()} loads the keys on a timer. A server publishes its key and loads the
 * others before its gateway opens, but the others load it only at their next refresh. A sandbox's call can reach any
 * replica, so an unknown {@code kid} loads the keys again, at most once per {@link #UNKNOWN_KID_RELOAD}: a token of a
 * replica that has just started verifies at once, and a flood of forged kids costs the store one read per interval.
 */
public class AttemptKeyring {

    private static final Logger log = LoggerFactory.getLogger(AttemptKeyring.class);

    /** The longest token life and a margin of 10 minutes; {@link #publish()} extends it while its server lives. */
    static final Duration KEPT_FOR = Duration.ofSeconds(AgentBindingLimits.MAX_TIMEOUT_SECONDS)
            .plus(SandboxLayout.RESULT_UPLOAD_GRACE)
            .plus(Duration.ofMinutes(10));

    /** The least time between two loads of the keys for an unknown {@code kid}. */
    static final Duration UNKNOWN_KID_RELOAD = Duration.ofSeconds(1);

    private final AttemptSigningKey key;
    private final AttemptKeyStore store;
    private final Clock clock;
    private final JWTVerifier ownVerifier;
    private final AtomicReference<Instant> lastUnknownKidReload = new AtomicReference<>(Instant.MIN);
    private volatile Map<String, JWTVerifier> verifiers;
    private volatile boolean published;

    public AttemptKeyring(AttemptSigningKey key, AttemptKeyStore store) {
        this(key, store, Clock.systemUTC());
    }

    AttemptKeyring(AttemptSigningKey key, AttemptKeyStore store, Clock clock) {
        this.key = key;
        this.store = store;
        this.clock = clock;
        this.ownVerifier = verifier(key.publicKey());
        this.verifiers = Map.of(key.kid(), ownVerifier);
    }

    /** The key to sign with, published before it signs anything. */
    AttemptSigningKey signingKey() {
        if (!published) publish();
        return key;
    }

    /** @return the verifier of this {@code kid}; {@code null} for a key that no live server published */
    @Nullable
    JWTVerifier verifierFor(String kid) {
        JWTVerifier known = verifiers.get(kid);
        if (known != null || !mayReloadForUnknownKid()) return known;
        try {
            refresh();
        } catch (RuntimeException failure) {
            // The timer's refresh counts store failures; a request keeps the known keys and refuses the kid.
            log.debug("Could not load the attempt keys for an unknown kid", failure);
        }
        return verifiers.get(kid);
    }

    private boolean mayReloadForUnknownKid() {
        Instant now = clock.instant();
        Instant last = lastUnknownKidReload.get();
        return !now.isBefore(last.plus(UNKNOWN_KID_RELOAD)) && lastUnknownKidReload.compareAndSet(last, now);
    }

    /** Publishes this server's key, and extends it for as long as this server lives. */
    public void publish() {
        store.publish(key.kid(), key.publicKey().getEncoded(), KEPT_FOR);
        // Names the row to delete when this server's key is revoked.
        if (!published) log.info("Published this server's attempt key: kid={}", key.kid());
        published = true;
    }

    /** Loads the live keys. A failed load leaves the known keys in place, so an outage of the store refuses no token. */
    public void refresh() {
        Map<String, JWTVerifier> known = verifiers;
        Map<String, JWTVerifier> next = new HashMap<>();
        store.live().forEach((kid, publicKey) -> {
            JWTVerifier verifier = known.get(kid);
            if (verifier == null) verifier = parsed(kid, publicKey);
            if (verifier != null) next.put(kid, verifier);
        });
        next.put(key.kid(), ownVerifier);
        verifiers = Map.copyOf(next);
    }

    public void removeExpired() {
        store.removeExpired();
    }

    private static @Nullable JWTVerifier parsed(String kid, byte[] publicKey) {
        try {
            var parsed = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKey));
            return verifier((RSAPublicKey) parsed);
        } catch (GeneralSecurityException | ClassCastException invalid) {
            log.warn("Ignoring an unreadable attempt public key: kid={}", kid, invalid);
            return null;
        }
    }

    private static JWTVerifier verifier(RSAPublicKey publicKey) {
        return JWT.require(Algorithm.RSA256(publicKey, null))
                .withIssuer(AttemptTokenIssuer.ISSUER)
                .withAudience(AttemptTokenIssuer.AUDIENCE)
                .acceptLeeway(5)
                .build();
    }
}
