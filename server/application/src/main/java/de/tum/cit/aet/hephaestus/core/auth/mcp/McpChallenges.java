package de.tum.cit.aet.hephaestus.core.auth.mcp;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.web.ApiPath;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

    /** The refusal reasons: the challenge's {@code error}, or {@code missing_token} for a request without a token. */
    private static final List<String> REASONS = List.of("missing_token", "invalid_token", "insufficient_scope");

    private final McpResources resources;
    private final Map<String, Counter> refusals;

    public McpChallenges(McpResources resources, MeterRegistry meterRegistry) {
        this.resources = resources;
        // Every reason registers at start (ADR 0110 decision 19); a second instance reads the same counters.
        this.refusals = REASONS.stream()
                .collect(Collectors.toUnmodifiableMap(
                        reason -> reason,
                        reason -> Counter.builder(CoreMetrics.MCP_AUTH_REFUSALS)
                                .description("Workspace MCP requests refused with a challenge, by reason")
                                .tag("reason", reason)
                                .register(meterRegistry)));
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure) {
        String error = failure instanceof InvalidBearerTokenException ? "invalid_token" : null;
        count(error == null ? "missing_token" : error);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge(slugOf(request), error, null));
    }

    /** Counts one refusal of a workspace MCP request. */
    void count(String reason) {
        Counter refused = refusals.get(reason);
        if (refused != null) {
            refused.increment();
        }
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
        String path = ApiPath.relative(request);
        if (path == null) {
            return null;
        }
        Matcher matcher = ENDPOINT.matcher(path);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
