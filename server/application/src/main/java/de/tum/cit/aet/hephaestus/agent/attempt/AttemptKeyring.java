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
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This worker's signing key, and every worker's public key. A token that one worker signed verifies on any worker, so
 * an attempt does not depend on the worker that started it.
 *
 * <p>Verification reads only memory: {@link #refresh()} loads the keys on a timer, and an unknown {@code kid} is refused
 * with no read, so a forged token costs the store nothing. A worker publishes its key and loads the others before its
 * gateway opens. If the store failed then, its first signature publishes the key, and for up to one refresh another
 * worker refuses that token as unknown. A caller that dials another worker must then ask again.
 */
public class AttemptKeyring {

    private static final Logger log = LoggerFactory.getLogger(AttemptKeyring.class);

    /** The longest token life and a margin of 10 minutes; {@link #publish()} extends it while its worker lives. */
    static final Duration KEPT_FOR = Duration.ofSeconds(AgentBindingLimits.MAX_TIMEOUT_SECONDS)
            .plus(SandboxLayout.RESULT_UPLOAD_GRACE)
            .plus(Duration.ofMinutes(10));

    private final AttemptSigningKey key;
    private final AttemptKeyStore store;
    private final JWTVerifier ownVerifier;
    private volatile Map<String, JWTVerifier> verifiers;
    private volatile boolean published;

    public AttemptKeyring(AttemptSigningKey key, AttemptKeyStore store) {
        this.key = key;
        this.store = store;
        this.ownVerifier = verifier(key.publicKey());
        this.verifiers = Map.of(key.kid(), ownVerifier);
    }

    /** The key to sign with, published before it signs anything. */
    AttemptSigningKey signingKey() {
        if (!published) publish();
        return key;
    }

    /** @return the verifier of this {@code kid}; {@code null} for a key that no live worker published */
    @Nullable
    JWTVerifier verifierFor(String kid) {
        return verifiers.get(kid);
    }

    /** Publishes this worker's key, and extends it for as long as this worker lives. */
    public void publish() {
        store.publish(key.kid(), key.publicKey().getEncoded(), KEPT_FOR);
        // Names the row to delete when this worker's key is revoked.
        if (!published) log.info("Published this worker's attempt key: kid={}", key.kid());
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
