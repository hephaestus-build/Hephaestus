package de.tum.cit.aet.hephaestus.core.webhook;

import jakarta.validation.constraints.AssertTrue;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Shared webhook configuration bound to {@code hephaestus.webhook.*}: the receiver's settings, the GitHub webhook
 * secret, and the routing key that GitLab and Outline hook registration use. See ADR 0008 and ADR 0066.
 *
 * <p>Incoming request size is capped at the {@code WebhookPayloadSizeFilter} before Spring
 * buffers the body — {@code server.tomcat.max-http-post-size} only enforces on form-encoded
 * payloads, so we don't rely on it for JSON webhooks.
 *
 * @param secret Shared secret that verifies GitHub webhook signatures. The server requires at least
 *     32 printable ASCII characters without spaces, also on an instance without GitHub. Must differ from both routing
 *     secrets.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.webhook")
public record WebhookProperties(
        @Nullable String secret,
        @DefaultValue TokenRotation tokenRotation,
        @DefaultValue Http http,
        @DefaultValue Routing routing) {
    /** Minimum HMAC-SHA256 secret length recommended by NIST SP 800-107. */
    public static final int MIN_SECRET_LENGTH = 32;

    /**
     * A routing key signs credentials bound to one connection, so it must not be {@link #secret}: that value is shared
     * with GitHub and readable by an administrator of the GitHub App or of any repository hook that holds it.
     */
    @AssertTrue(message = "hephaestus.webhook.routing secrets must differ from hephaestus.webhook.secret")
    private boolean isRoutingKeyIndependentOfSharedSecret() {
        if (routing == null || secret == null || secret.isBlank()) {
            return true;
        }
        return !secret.equals(routing.secret()) && !secret.equals(routing.previousSecret());
    }

    /** Redacts {@code secret} so accidental {@code log.info("config: {}", props)} doesn't leak it. */
    @Override
    public String toString() {
        return ("WebhookProperties[secret="
                + (secret == null || secret.isBlank() ? "<unset>" : "<redacted>")
                + ", tokenRotation="
                + tokenRotation
                + ", http="
                + http
                + ", routing="
                + routing
                + "]");
    }

    /**
     * Rotation of the access token that each GitLab connection holds.
     *
     * @param thresholdDays Days before a GitLab connection token expires at which Hephaestus rotates it. Set 0 to only
     *     report the expiring token without rotating it.
     * @param validityDays Lifetime in days of the new token that a GitLab token rotation requests. Must be at least 1.
     */
    public record TokenRotation(
            @DefaultValue("7") int thresholdDays,
            @DefaultValue("90") int validityDays) {
        public TokenRotation {
            if (thresholdDays < 0) {
                throw new IllegalArgumentException("tokenRotation.thresholdDays must be >= 0, got: " + thresholdDays);
            }
            if (validityDays < 1) {
                throw new IllegalArgumentException("tokenRotation.validityDays must be >= 1, got: " + validityDays);
            }
        }
    }

    /**
     * Enforced by {@code WebhookPayloadSizeFilter} via {@code Content-Length} header. Tomcat's
     * {@code max-http-post-size} only caps form bodies, so it can't enforce JSON request size on
     * its own.
     */
    public record Http(@DefaultValue("26214400") long maxPayloadBytes) {
        public Http {
            if (maxPayloadBytes < 1) {
                throw new IllegalArgumentException("http.maxPayloadBytes must be >= 1, got: " + maxPayloadBytes);
            }
        }
    }

    /**
     * Routing keys for the webhooks Hephaestus registers: the connection-scoped GitLab credential and each Outline
     * subscription's signing secret. {@code secret} signs new hooks; {@code previousSecret} is still accepted while an
     * operator rotates, until every hook signed with it has been replaced. Without {@code secret} neither is registered.
     *
     * @param secret Key that signs the webhook token of each GitLab connection and derives the signing secret of each
     *     Outline subscription. At least 32 characters, and must differ from the webhook secret. GitLab and Outline
     *     webhook registration is skipped when it is unset.
     * @param previousSecret Previous routing key that the receiver still accepts during a rotation. Keep it until every
     *     hook signed with it is replaced. Requires routing.secret and must differ from it.
     */
    public record Routing(@Nullable String secret, @Nullable String previousSecret) {
        public Routing {
            secret = blankToNull(secret);
            previousSecret = blankToNull(previousSecret);
            requireKeyLength("routing.secret", secret);
            requireKeyLength("routing.previousSecret", previousSecret);
            if (secret == null && previousSecret != null) {
                throw new IllegalArgumentException("routing.previousSecret requires routing.secret");
            }
            if (secret != null && secret.equals(previousSecret)) {
                throw new IllegalArgumentException("routing.previousSecret must differ from routing.secret");
            }
        }

        private static @Nullable String blankToNull(@Nullable String value) {
            return value == null || value.isBlank() ? null : value;
        }

        private static void requireKeyLength(String name, @Nullable String value) {
            if (value != null && value.length() < MIN_SECRET_LENGTH) {
                throw new IllegalArgumentException(name + " must be at least " + MIN_SECRET_LENGTH + " characters");
            }
        }

        @Override
        public String toString() {
            return "Routing[secret=" + (secret == null ? "<unset>" : "<redacted>")
                    + ", previousSecret="
                    + (previousSecret == null ? "<unset>" : "<redacted>")
                    + "]";
        }
    }
}
