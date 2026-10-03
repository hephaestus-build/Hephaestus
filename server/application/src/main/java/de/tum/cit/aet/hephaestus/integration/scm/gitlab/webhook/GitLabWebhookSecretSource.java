package de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSecretSource;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * App-global GitLab webhook secret source. Returns the shared
 * {@code hephaestus.webhook.secret} for both verification modes: the plaintext path
 * byte-compares this value, the {@code whsec_*} path interprets a {@code whsec_<b64>}
 * prefix as a Standard Webhooks signing secret and treats anything else as opaque bytes.
 *
 * <p>Per-workspace {@code whsec_*} secrets would require {@code Scope.WORKSPACE} wiring
 * plus per-Connection credential storage, so Standard Webhooks setups share one instance-wide
 * signing secret.
 */
@Component
public class GitLabWebhookSecretSource implements WebhookSecretSource {

    private final byte[] sharedSecretBytes;

    public GitLabWebhookSecretSource(@Value("${hephaestus.webhook.secret:}") String sharedSecret) {
        this.sharedSecretBytes = sharedSecret == null || sharedSecret.isBlank()
                ? new byte[0]
                : sharedSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public Scope scope() {
        // APP_GLOBAL today. When per-workspace whsec_* lands, this flips to WORKSPACE
        // and getSecret() reads from ConnectionService via SecretLookup.workspaceId.
        return Scope.APP_GLOBAL;
    }

    @Override
    public Optional<byte[]> getSecret(SecretLookup lookup) {
        if (sharedSecretBytes.length == 0) {
            return Optional.empty();
        }
        // Defensive copy: callers MUST NOT be able to mutate our cached secret.
        byte[] copy = new byte[sharedSecretBytes.length];
        System.arraycopy(sharedSecretBytes, 0, copy, 0, sharedSecretBytes.length);
        return Optional.of(copy);
    }
}
