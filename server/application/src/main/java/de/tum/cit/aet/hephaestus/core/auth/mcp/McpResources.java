package de.tum.cit.aet.hephaestus.core.auth.mcp;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * The public URIs of the authorization server and of each workspace MCP endpoint. They come from the
 * configured issuer and API base path, never from a request's Host header.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
public class McpResources {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9-]{1,64}");

    private final AuthProperties properties;

    McpResources(AuthProperties properties) {
        this.properties = properties;
    }

    public String issuer() {
        return properties.issuer().toString();
    }

    /** The canonical URI of a workspace's MCP endpoint (RFC 8707 § 2): the resource and the token audience. */
    public String resourceFor(String slug) {
        return properties.apiBaseUrl() + "/workspaces/" + slug + "/mcp";
    }

    /** The workspace slug that a resource URI of this issuer names; empty for any other URI. */
    public Optional<String> slugOf(String resource) {
        String prefix = properties.apiBaseUrl() + "/workspaces/";
        if (!resource.startsWith(prefix) || !resource.endsWith("/mcp")) {
            return Optional.empty();
        }
        String slug = resource.substring(prefix.length(), resource.length() - "/mcp".length());
        return SLUG.matcher(slug).matches() ? Optional.of(slug) : Optional.empty();
    }

    /** Where RFC 9728 places the protected resource metadata of a workspace endpoint. */
    public String resourceMetadataFor(String slug) {
        String origin =
                properties.issuer().getScheme() + "://" + properties.issuer().getRawAuthority();
        return origin + "/.well-known/oauth-protected-resource" + properties.apiBasePath() + "/workspaces/" + slug
                + "/mcp";
    }

    public String authorizationEndpoint() {
        return properties.apiBaseUrl() + "/mcp/oauth/authorize";
    }

    public String tokenEndpoint() {
        return properties.apiBaseUrl() + "/mcp/oauth/token";
    }
}
