package de.tum.cit.aet.hephaestus.core.runtime.hub.auth;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bound to {@code hephaestus.worker.hub.token.*}. JWK rotation: list every key (active + previous)
 * in {@link #keys()} and point {@link #activeKid()} at the signing key; keep the previous key for
 * one TTL window so already-issued tokens stay verifiable.
 *
 * @param registrationToken Token that workers present to the server to obtain their access token. Give each worker
 *     the same value as hephaestus.worker.control.registration-token. Worker registration is closed when it is unset.
 */
@ConfigurationProperties(prefix = "hephaestus.worker.hub.token")
public record WorkerTokenProperties(
        @DefaultValue("hephaestus-hub") String issuer,
        @DefaultValue("hephaestus-worker") String audience,
        @DefaultValue("1h") Duration ttl,
        @Nullable String registrationToken,
        @DefaultValue List<KeyEntry> keys,
        @Nullable String activeKid) {
    public WorkerTokenProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("token.issuer must not be blank");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("token.audience must not be blank");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("token.ttl must be positive, got: " + ttl);
        }
    }

    /** @return {@code true} iff a registration token is configured (otherwise exchange is closed). */
    public boolean isExchangeEnabled() {
        return registrationToken != null && !registrationToken.isBlank();
    }

    @Override
    public String toString() {
        return ("WorkerTokenProperties[issuer=" + issuer
                + ", audience="
                + audience
                + ", ttl="
                + ttl
                + ", registrationToken="
                + (registrationToken == null || registrationToken.isBlank() ? "<unset>" : "<redacted>")
                + ", keys="
                + (keys == null ? 0 : keys.size())
                + " key(s)"
                + ", activeKid="
                + (activeKid == null ? "<unset>" : activeKid)
                + "]");
    }

    /** One entry in the key ring; the PEM is PKCS#8 RSA private key (public derived). */
    public record KeyEntry(String kid, String privateKey) {
        public KeyEntry {
            if (kid == null || kid.isBlank()) {
                throw new IllegalArgumentException("KeyEntry.kid must not be blank");
            }
            if (privateKey == null || privateKey.isBlank()) {
                throw new IllegalArgumentException("KeyEntry.privateKey (PEM) must not be blank for kid=" + kid);
            }
        }

        @Override
        public String toString() {
            return "KeyEntry[kid=" + kid + ", privateKey=<redacted>]";
        }
    }
}
