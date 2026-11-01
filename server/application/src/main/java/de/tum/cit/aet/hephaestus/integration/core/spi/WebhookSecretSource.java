package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Map;
import java.util.Optional;

/**
 * Per-kind signing-secret resolution.
 *
 * <p>GitHub uses it with {@link Scope#APP_GLOBAL}: one shared {@code hephaestus.webhook.secret}. GitLab and Outline
 * verify a route credential on their connection endpoints instead.
 */
public interface WebhookSecretSource {
    IntegrationKind kind();

    Scope scope();

    /** Resolves the signing secret bytes for an incoming webhook. */
    Optional<byte[]> getSecret(SecretLookup lookup);

    enum Scope {
        APP_GLOBAL,
    }

    /** Best-effort identification of the request before signature verification: its headers. */
    record SecretLookup(Map<String, String> headers) {}
}
