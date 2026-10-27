package de.tum.cit.aet.hephaestus.integration.outline.webhook;

import de.tum.cit.aet.hephaestus.core.webhook.RoutingKeys;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The signing secret of an Outline change-notification subscription, derived from
 * {@code hephaestus.webhook.routing.secret} and a random route id instead of stored. The subscription posts to
 * {@code /webhooks/outline/{keyId}/{routeId}}, so the receiver recomputes the secret from the URL and verifies a
 * delivery without a database. The server stores only the route ({@code keyId/routeId}, not a secret) and drops a
 * delivery whose route is not the one its subscription registered.
 *
 * <p>A route id is random, so every registration gets a new secret. The key id names the routing key, so rotating
 * the key (with the old one kept as {@code previous-secret}) keeps existing subscriptions verifiable until the
 * reconcile registers them again under the new key.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineRouteCredential {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Pattern ROUTE_ID = Pattern.compile("[A-Za-z0-9_-]{22}");

    /** A route id and the secret Outline signs its deliveries with. */
    public record Issued(String keyId, String routeId, String signingSecret) {

        /** The route as the delivery URL names it and the server stores it. */
        public String route() {
            return keyId + "/" + routeId;
        }

        @Override
        public String toString() {
            return "Issued[keyId=" + keyId + ", routeId=" + routeId + ", signingSecret=<redacted>]";
        }
    }

    private final RoutingKeys keys;

    public OutlineRouteCredential(WebhookProperties properties) {
        this.keys = new RoutingKeys(properties.routing(), "hephaestus-outline-route-key");
    }

    /** Whether new subscriptions can be registered. */
    public boolean isConfigured() {
        return keys.current().isPresent();
    }

    /** Whether {@code route} was issued under the current key, so it needs no registration under a newer one. */
    public boolean isCurrent(String route) {
        return keys.current().filter(key -> route.startsWith(key.id() + "/")).isPresent();
    }

    /** A new route under the current key. */
    public Issued issue() {
        RoutingKeys.Key key = keys.current()
                .orElseThrow(() -> new IllegalStateException("hephaestus.webhook.routing.secret is not configured"));
        byte[] random = new byte[16];
        SECURE_RANDOM.nextBytes(random);
        String routeId = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        return new Issued(key.id(), routeId, signingSecret(key, routeId));
    }

    /** The secret of the route the URL names, or empty when its key is not accepted or the route id is malformed. */
    public Optional<byte[]> signingSecret(String keyId, String routeId) {
        if (!ROUTE_ID.matcher(routeId).matches()) {
            return Optional.empty();
        }
        return keys.accepted(keyId).map(key -> signingSecret(key, routeId).getBytes(StandardCharsets.UTF_8));
    }

    private static String signingSecret(RoutingKeys.Key key, String routeId) {
        return key.mac("hephaestus-outline-signing-secret\n" + routeId);
    }
}
