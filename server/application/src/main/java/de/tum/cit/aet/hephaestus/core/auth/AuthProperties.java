package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Authentication configuration. Operator guidance: {@code docs/admin/configuration-readiness.mdx}.
 *
 * @param issuer canonical {@code iss} claim
 * @param apiBasePath public prefix stripped by the reverse proxy, prepended to browser-facing OAuth
 *                   URLs. Tomcat's native forwarded-header support does not restore
 *                   {@code X-Forwarded-Prefix}; see {@code ProxyTrustGuard} before changing that strategy.
 * @param audience expected {@code aud} claim
 * @param accessTtl access-token lifetime, bounded by the absolute session deadline
 * @param cookieName access-token cookie name; {@code __Host-} requires Secure, Path=/, and no Domain
 * @param stateCookieKey base64-encoded 32-byte AES key sealing OAuth state and auth-intent cookies;
 *                       required outside development
 * @param deleteCooldown interval during which a soft-deleted account can be restored
 * @param loginProviders initial login providers keyed by registration ID; seeding never overwrites
 *                       later administrator edits
 * @param bootstrapAdmins identities promoted to instance admin on login, in
 *                        {@code registrationId:@username} or {@code registrationId:subject} form.
 *                        Use a stable subject where usernames can be reclaimed; email is never matched.
 * @param bootstrapToken optional deployment-controlled token for recovering an instance with no active
 *                       admin; blank disables the endpoint
 * @param impersonationMaxLifetime absolute impersonation deadline; see
 *                                 {@code docs/contributor/instance-admin.md}
 * @param sessionMaxLifetime absolute session deadline set at login and preserved through refresh
 * @param stepUpMaxAge maximum sign-in age for sensitive actions; refresh does not reset sign-in time
 * @param devLoginEnabled passwordless local sign-in; rejected under the production profile and unsafe
 *                        on an internet-exposed deployment
 * @param cookieSecure whether auth and CSRF cookies use Secure and the {@code __Host-} prefix;
 *                     disabling is restricted to non-production HTTP development
 */
@ConfigurationProperties(prefix = "hephaestus.auth")
public record AuthProperties(
        @DefaultValue("http://localhost:8080") URI issuer,
        @DefaultValue("") String apiBasePath,
        @DefaultValue("hephaestus-spa") String audience,
        @DefaultValue("24h") Duration accessTtl,
        @DefaultValue(DEFAULT_COOKIE_NAME) String cookieName,
        @DefaultValue("") String stateCookieKey,
        @DefaultValue("48h") Duration deleteCooldown,
        Map<String, LoginProviderSeed> loginProviders,
        @DefaultValue List<String> bootstrapAdmins,
        @DefaultValue("") String bootstrapToken,
        @DefaultValue("1h") Duration impersonationMaxLifetime,
        @DefaultValue("7d") Duration sessionMaxLifetime,
        @DefaultValue("5m") Duration stepUpMaxAge,
        @DefaultValue("false") boolean devLoginEnabled,
        @DefaultValue("true") boolean cookieSecure) {
    public AuthProperties {
        loginProviders = loginProviders == null ? Map.of() : loginProviders;
        apiBasePath = normalizeApiBasePath(apiBasePath);
    }

    /** Normalizes a proxy prefix to a leading slash without trailing slashes; blank means root. */
    private static String normalizeApiBasePath(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim().replaceAll("/+$", "");
        if (trimmed.isEmpty()) {
            return "";
        }
        trimmed = trimmed.replaceAll("^/+", "/");
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    public static final String DEFAULT_COOKIE_NAME = "__Host-HEPHAESTUS_AT";

    /**
     * Initial provider settings. GitLab uses {@code baseUrl}; GitHub uses github.com. Scopes are
     * derived by {@code LoginProviderService}, not configurable here, to preserve provider constraints.
     */
    public record LoginProviderSeed(
            LoginProvider.ProviderType type,
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String clientId,
            @DefaultValue("") String clientSecret,
            @DefaultValue("") String displayName) {
        /** Incomplete credentials must not expose a login provider that cannot exchange tokens. */
        public boolean configured() {
            return !isBlank(clientId) && !isBlank(clientSecret);
        }

        public boolean partiallyConfigured() {
            return !configured() && (!isBlank(clientId) || !isBlank(clientSecret));
        }

        /** The blank credential half, for an actionable "set this env var" diagnostic; empty when both are set. */
        public String missingCredentialField() {
            if (isBlank(clientId)) {
                return "client-id";
            }
            if (isBlank(clientSecret)) {
                return "client-secret";
            }
            return "";
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
