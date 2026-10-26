package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Map;
import java.util.Optional;

/**
 * Per-kind signing-secret resolution.
 *
 * <p>Vendors scope their webhook signing secret differently:
 * <ul>
 *   <li>GitHub: APP_GLOBAL — one shared {@code hephaestus.webhook.secret}
 *   <li>GitLab plaintext: APP_GLOBAL
 *   <li>GitLab {@code whsec_*} HMAC: WORKSPACE — per-Connection in {@code GitLabConfig.signingSecret}
 * </ul>
 */
public interface WebhookSecretSource {
    IntegrationKind kind();

    Scope scope();

    /** Resolves the signing secret bytes for an incoming webhook. */
    Optional<byte[]> getSecret(SecretLookup lookup);

    enum Scope {
        APP_GLOBAL,
        WORKSPACE,
    }

    /** Best-effort identification of the request before signature verification: its headers. */
    record SecretLookup(Map<String, String> headers) {}
}
