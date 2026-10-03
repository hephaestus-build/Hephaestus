package de.tum.cit.aet.hephaestus.integration.scm.github.webhook;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSecretSource;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * GitHub adapter for {@link WebhookSecretSource}. GitHub Apps share a single
 * app-wide signing secret across all installations, so the scope is
 * {@link Scope#APP_GLOBAL} and the secret is read from
 * {@code hephaestus.webhook.secret}.
 *
 * <p>Returns {@link Optional#empty()} when the secret is unconfigured; the verifier
 * upstream surfaces this as an "invalid: missing-secret" reject rather than crashing,
 * so dev environments without a configured secret degrade gracefully.
 */
@Component
public class GitHubWebhookSecretSource implements WebhookSecretSource {

    private final WebhookProperties webhookProperties;

    public GitHubWebhookSecretSource(WebhookProperties webhookProperties) {
        this.webhookProperties = webhookProperties;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public Scope scope() {
        return Scope.APP_GLOBAL;
    }

    @Override
    public Optional<byte[]> getSecret(SecretLookup lookup) {
        String secret = webhookProperties.secret();
        if (secret == null || secret.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(secret.getBytes(StandardCharsets.UTF_8));
    }
}
