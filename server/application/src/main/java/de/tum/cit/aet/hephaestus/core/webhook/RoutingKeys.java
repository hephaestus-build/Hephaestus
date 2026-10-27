package de.tum.cit.aet.hephaestus.core.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * The keys of {@code hephaestus.webhook.routing.secret} for one purpose: the current key signs, and the previous key is
 * still accepted while a rotation completes. A key id names a key in a URL without revealing it, and each purpose
 * derives its own ids, so a GitLab hook's key id never names an Outline route's key.
 */
public final class RoutingKeys {

    /** One routing key and the id that names it. */
    @SuppressWarnings("ArrayRecordComponent") // HMAC key bytes; never compared, hashed or printed
    public record Key(String id, byte[] secret) {

        /** HMAC-SHA256 of {@code message} under this key, base64url without padding. */
        public String mac(String message) {
            return RoutingKeys.mac(secret, message);
        }

        @Override
        public String toString() {
            return "Key[id=" + id + ", secret=<redacted>]";
        }
    }

    private final @Nullable Key current;
    private final @Nullable Key previous;

    public RoutingKeys(WebhookProperties.Routing routing, String purpose) {
        this.current = key(routing.secret(), purpose);
        this.previous = key(routing.previousSecret(), purpose);
    }

    /** The key that signs new routes; empty when no routing secret is configured. */
    public Optional<Key> current() {
        return Optional.ofNullable(current);
    }

    /** The key {@code id} names, when it is the current or the previous key. */
    public Optional<Key> accepted(String id) {
        if (current != null && current.id().equals(id)) {
            return Optional.of(current);
        }
        if (previous != null && previous.id().equals(id)) {
            return Optional.of(previous);
        }
        return Optional.empty();
    }

    private static @Nullable Key key(@Nullable String secret, String purpose) {
        if (secret == null) {
            return null;
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        return new Key(mac(bytes, purpose).substring(0, 16), bytes);
    }

    private static String mac(byte[] secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] tag = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(tag);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
