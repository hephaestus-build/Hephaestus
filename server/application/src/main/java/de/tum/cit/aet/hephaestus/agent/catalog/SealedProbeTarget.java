package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.security.SecretBinding;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import org.jspecify.annotations.Nullable;

/**
 * The projection that {@link LlmProbeTarget} comes from: the stored, sealed API key and the row key it is bound to.
 *
 * @param workspaceId {@code null} for an instance connection
 */
record SealedProbeTarget(
        String baseUrl,
        LlmAuthMode authMode,
        @Nullable String sealedApiKey,
        LlmApiProtocol apiProtocol,
        @Nullable Long workspaceId,
        String slug) {

    /** An instance connection, which belongs to no workspace. */
    SealedProbeTarget(
            String baseUrl,
            LlmAuthMode authMode,
            @Nullable String sealedApiKey,
            LlmApiProtocol apiProtocol,
            String slug) {
        this(baseUrl, authMode, sealedApiKey, apiProtocol, null, slug);
    }

    LlmProbeTarget open(SystemSecrets secrets) {
        SecretBinding binding = workspaceId == null
                ? LlmConnection.secretBinding(slug)
                : WorkspaceLlmConnection.secretBinding(workspaceId, slug);
        String apiKey = sealedApiKey == null ? null : secrets.openText(sealedApiKey, binding);
        return new LlmProbeTarget(baseUrl, authMode, apiKey, apiProtocol);
    }
}
