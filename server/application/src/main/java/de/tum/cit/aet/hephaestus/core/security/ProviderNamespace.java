package de.tum.cit.aet.hephaestus.core.security;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The key by which two {@code identity_provider} rows of one type are one subject namespace. For a Git provider, Slack
 * or Outline it is the {@link ScmOrigin}, so two spellings of one instance match. An OIDC subject is unique only
 * within its issuer, and issuers compare as exact strings (OpenID Connect Core § 5.7, Discovery § 4.3), as the
 * identity key does: the key is the stored issuer without a trailing slash.
 */
public final class ProviderNamespace {

    private ProviderNamespace() {}

    /** The namespace key of a provider row, or empty when its URL has no valid origin. */
    public static Optional<String> of(String providerType, @Nullable String serverUrl) {
        Optional<String> origin = ScmOrigin.of(serverUrl);
        if (!"OIDC".equals(providerType) || serverUrl == null) return origin;
        return origin.map(ignored -> serverUrl.trim().replaceAll("/+$", ""));
    }

    /** Whether two rows of {@code providerType} are one namespace. A row without a valid key matches none. */
    public static boolean same(String providerType, @Nullable String serverUrl, @Nullable String otherServerUrl) {
        Optional<String> key = of(providerType, serverUrl);
        return key.isPresent() && key.equals(of(providerType, otherServerUrl));
    }
}
