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

    /**
     * Resolves the signing secret bytes for an incoming webhook. Vendors that scope by
     * subscription extract the subscription identifier from the headers themselves.
     */
    Optional<byte[]> getSecret(SecretLookup lookup);

    enum Scope {
        APP_GLOBAL,
        WORKSPACE,
        SUBSCRIPTION,
    }

    /**
     * Best-effort identification of the request before signature verification. Carries the
     * request headers and raw body: header-scoped vendors (GitHub/GitLab) read only
     * {@link #headers()} and ignore the body, while subscription-scoped vendors (Outline)
     * parse a subscription id out of {@link #body()} to select the stored secret.
     */
    @SuppressWarnings("ArrayRecordComponent") // raw webhook body; never compared, hashed or printed
    record SecretLookup(Map<String, String> headers, byte[] body) {
        /** Header-only lookup — the body is empty for vendors that scope by header alone. */
        public SecretLookup(Map<String, String> headers) {
            this(headers, new byte[0]);
        }
    }
}
