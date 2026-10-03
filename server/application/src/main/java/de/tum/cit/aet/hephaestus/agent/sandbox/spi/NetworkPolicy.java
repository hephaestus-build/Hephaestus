package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Network access policy for a sandboxed container.
 *
 * <p>When {@code internetAccess} is {@code false}, the container runs on a Docker
 * {@code --internal} network with zero external connectivity. The only reachable endpoint
 * is the LLM proxy at {@code llmProxyUrl}, accessed via app-server multi-homing on the job
 * network.
 *
 * <p>{@code llmProxyUrl} is validated at construction: when non-blank it must be an
 * absolute {@code http(s)} URL. Validation lives in the compact constructor so SPI-build-
 * time errors fail loud, including when bound from YAML via {@code @ConfigurationProperties}.
 *
 * <p><b>Template placeholders:</b> Docker sandbox adapters resolve {@code {appServerIp}}
 * to the runtime-discovered container IP before launching the LLM proxy connection
 * (see {@code DockerSandboxAdapter#PROXY_URL_PLACEHOLDER}). Validation must accept the
 * unresolved template form — at SPI-build time the placeholder is intentionally still
 * present. We perform a light prefix/scheme check on template URLs and defer full URI
 * validation to the adapter (which sees the resolved value).
 */
public record NetworkPolicy(
        boolean internetAccess,
        @Nullable String llmProxyUrl,
        @Nullable String llmProxyToken) {
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** Matches the documented template placeholder produced before adapter resolution. */
    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("\\{[a-zA-Z][a-zA-Z0-9_]*\\}");

    public NetworkPolicy {
        if (llmProxyUrl != null && !llmProxyUrl.isBlank()) {
            requireAbsoluteHttp(llmProxyUrl);
        }
    }

    private static void requireAbsoluteHttp(String url) {
        if (TEMPLATE_PLACEHOLDER.matcher(url).find()) {
            // Template form: URI.create would choke on {placeholder}. Validate only what
            // we can: the scheme must be an absolute http(s) prefix. The adapter performs
            // the strict URI check after substitution.
            requireHttpSchemePrefix(url);
            return;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("llmProxyUrl is not a valid URI: " + url, ex);
        }
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("llmProxyUrl must be absolute: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("llmProxyUrl must use http or https: " + url);
        }
    }

    private static void requireHttpSchemePrefix(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "llmProxyUrl must start with http:// or https:// (templated form): " + url);
        }
    }
}
