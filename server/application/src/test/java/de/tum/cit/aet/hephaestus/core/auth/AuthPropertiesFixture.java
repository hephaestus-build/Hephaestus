package de.tum.cit.aet.hephaestus.core.auth;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Shared {@link AuthProperties} builder for unit tests, so the record construction lives in one place.
 * Values are the production defaults; vary only the component under test.
 */
public final class AuthPropertiesFixture {

    private AuthPropertiesFixture() {}

    /** Production defaults (no proxy prefix, no seeded providers). */
    public static AuthProperties defaults() {
        return build("", Map.of(), Duration.ofMinutes(5));
    }

    /** Defaults with the given {@code apiBasePath} (the constructor normalizes it). */
    public static AuthProperties withApiBasePath(String apiBasePath) {
        return build(apiBasePath, Map.of(), Duration.ofMinutes(5));
    }

    /** Defaults with the configured public API origin and prefix. */
    public static AuthProperties withApiBase(URI issuer, String apiBasePath) {
        return build(issuer, apiBasePath, Map.of(), Duration.ofMinutes(5), List.of(), true);
    }

    /** Defaults with the given recent-sign-in window. */
    public static AuthProperties withStepUpMaxAge(Duration stepUpMaxAge) {
        return build("", Map.of(), stepUpMaxAge);
    }

    /** Defaults with the given installed Chrome extension ids. */
    public static AuthProperties withBrowserExtensionIds(List<String> ids) {
        return build("", Map.of(), Duration.ofMinutes(5), ids);
    }

    /** Defaults with the given seeded login providers. */
    public static AuthProperties withLoginProviders(Map<String, AuthProperties.LoginProviderSeed> loginProviders) {
        return build("", loginProviders, Duration.ofMinutes(5));
    }

    private static AuthProperties build(
            String apiBasePath, Map<String, AuthProperties.LoginProviderSeed> providers, Duration stepUpMaxAge) {
        return build(apiBasePath, providers, stepUpMaxAge, List.of());
    }

    private static AuthProperties build(
            String apiBasePath,
            Map<String, AuthProperties.LoginProviderSeed> providers,
            Duration stepUpMaxAge,
            List<String> browserExtensionIds) {
        return build(apiBasePath, providers, stepUpMaxAge, browserExtensionIds, true);
    }

    /** Defaults that ask for a signing key created at or after {@code notBefore}. */
    public static AuthProperties withSigningKeyNotBefore(Instant notBefore) {
        AuthProperties d = defaults();
        return new AuthProperties(
                d.issuer(),
                d.apiBasePath(),
                d.audience(),
                d.accessTtl(),
                d.cookieName(),
                d.stateCookieKey(),
                d.loginProviders(),
                d.bootstrapAdmins(),
                d.bootstrapToken(),
                d.sessionMaxLifetime(),
                d.stepUpMaxAge(),
                d.assuranceEnforced(),
                d.devLoginEnabled(),
                d.cookieSecure(),
                d.browserExtensionIds(),
                notBefore);
    }

    public static AuthProperties withoutAssurance() {
        return build("", Map.of(), Duration.ofMinutes(5), List.of(), false);
    }

    private static AuthProperties build(
            String apiBasePath,
            Map<String, AuthProperties.LoginProviderSeed> providers,
            Duration stepUpMaxAge,
            List<String> browserExtensionIds,
            boolean assuranceEnforced) {
        return build(
                URI.create("http://localhost:8080"),
                apiBasePath,
                providers,
                stepUpMaxAge,
                browserExtensionIds,
                assuranceEnforced);
    }

    private static AuthProperties build(
            URI issuer,
            String apiBasePath,
            Map<String, AuthProperties.LoginProviderSeed> providers,
            Duration stepUpMaxAge,
            List<String> browserExtensionIds,
            boolean assuranceEnforced) {
        return new AuthProperties(
                issuer,
                apiBasePath,
                "hephaestus-spa",
                Duration.ofHours(24),
                AuthProperties.DEFAULT_COOKIE_NAME,
                "",
                providers,
                List.of(),
                "",
                Duration.ofDays(7),
                stepUpMaxAge,
                assuranceEnforced,
                false,
                true,
                browserExtensionIds,
                null);
    }
}
