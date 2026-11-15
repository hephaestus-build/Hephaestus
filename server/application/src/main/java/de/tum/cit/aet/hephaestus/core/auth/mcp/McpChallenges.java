package de.tum.cit.aet.hephaestus.core.auth.mcp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * The {@code WWW-Authenticate} challenges of a workspace MCP endpoint: every 401 and 403 names the
 * resource's metadata (RFC 9728 § 5.1), so a client can find the authorization server.
 */
public final class McpChallenges implements AuthenticationEntryPoint {

    private static final Pattern ENDPOINT = Pattern.compile("^/workspaces/([a-z0-9-]{1,64})/mcp$");

    private final McpResources resources;

    public McpChallenges(McpResources resources) {
        this.resources = resources;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure) {
        String error = failure instanceof InvalidBearerTokenException ? "invalid_token" : null;
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge(slugOf(request), error, null));
    }

    /** A {@code Bearer} challenge; {@code error} and {@code scope} are optional parameters. */
    public String challenge(@Nullable String slug, @Nullable String error, @Nullable String scope) {
        StringBuilder value = new StringBuilder("Bearer");
        String separator = " ";
        if (error != null) {
            value.append(separator).append("error=\"").append(error).append('"');
            separator = ", ";
        }
        if (scope != null) {
            value.append(separator).append("scope=\"").append(scope).append('"');
            separator = ", ";
        }
        if (slug != null) {
            value.append(separator)
                    .append("resource_metadata=\"")
                    .append(resources.resourceMetadataFor(slug))
                    .append('"');
        }
        return value.toString();
    }

    static @Nullable String slugOf(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Matcher matcher = ENDPOINT.matcher(path);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
