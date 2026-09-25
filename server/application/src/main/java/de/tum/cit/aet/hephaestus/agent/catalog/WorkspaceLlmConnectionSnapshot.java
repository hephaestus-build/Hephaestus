package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.workspace.spi.LlmConnectionPlatform;
import java.net.URI;
import java.net.URISyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * Audit snapshot of a workspace's "bring your own" LLM connection. A connection row is the one place a
 * credential lives and the audit table cannot be edited afterwards, so the snapshot records only
 * non-secret facts: {@link #llmApiKeySet} instead of the key, and a credential-free {@code baseUrl}.
 */
record WorkspaceLlmConnectionSnapshot(
        String slug,
        String displayName,
        @Nullable LlmConnectionPlatform connectionPlatform,
        @Nullable String baseUrl,
        String apiProtocol,
        LlmAuthMode authMode,
        boolean llmApiKeySet,
        boolean enabled)
        implements ConfigAuditSnapshot {
    static WorkspaceLlmConnectionSnapshot of(WorkspaceLlmConnection c) {
        return new WorkspaceLlmConnectionSnapshot(
                c.getSlug(),
                c.getDisplayName(),
                c.getConnectionPlatform(),
                credentialFreeBaseUrl(c.getBaseUrl()),
                c.getApiProtocol(),
                c.getAuthMode(),
                c.getApiKey() != null && !c.getApiKey().isBlank(),
                c.isEnabled());
    }

    /**
     * Drops userinfo, query and fragment — the three places a credential can hide in a URL. Unparseable
     * input collapses to a marker rather than being passed through.
     */
    private static @Nullable String credentialFreeBaseUrl(@Nullable String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url);
            if (uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null) {
                return url;
            }
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (URISyntaxException e) {
            return "<unparseable>";
        }
    }
}
