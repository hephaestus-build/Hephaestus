package de.tum.cit.aet.hephaestus.core.auth;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Shared {@link AuthProperties} builder for unit tests, so the record construction lives in one place.
 * Values are the production defaults; vary only the component under test.
 */
public final class AuthPropertiesFixture {

    private AuthPropertiesFixture() {}

    /** Production defaults (no seeded providers). */
    public static AuthProperties defaults() {
        return build(Map.of(), Duration.ofMinutes(5));
    }

    /** Defaults with the configured public origin. */
    public static AuthProperties withIssuer(URI issuer) {
        return build(issuer, Map.of(), Duration.ofMinutes(5), List.of(), true);
    }

    /** Defaults with the given recent-sign-in window. */
    public static AuthProperties withStepUpMaxAge(Duration stepUpMaxAge) {
        return build(Map.of(), stepUpMaxAge);
    }

    /** Defaults with the given installed Chrome extension ids. */
    public static AuthProperties withBrowserExtensionIds(List<String> ids) {
        return build(Map.of(), Duration.ofMinutes(5), ids);
    }

    /** Defaults with the given seeded login providers. */
    public static AuthProperties withLoginProviders(Map<String, AuthProperties.LoginProviderSeed> loginProviders) {
        return build(loginProviders, Duration.ofMinutes(5));
    }

    private static AuthProperties build(
            Map<String, AuthProperties.LoginProviderSeed> providers, Duration stepUpMaxAge) {
        return build(providers, stepUpMaxAge, List.of());
    }

    private static AuthProperties build(
            Map<String, AuthProperties.LoginProviderSeed> providers,
            Duration stepUpMaxAge,
            List<String> browserExtensionIds) {
        return build(providers, stepUpMaxAge, browserExtensionIds, true);
    }

    public static AuthProperties withoutAssurance() {
        return build(Map.of(), Duration.ofMinutes(5), List.of(), false);
    }

    private static AuthProperties build(
            Map<String, AuthProperties.LoginProviderSeed> providers,
            Duration stepUpMaxAge,
            List<String> browserExtensionIds,
            boolean assuranceEnforced) {
        return build(
                URI.create("http://localhost:8080"), providers, stepUpMaxAge, browserExtensionIds, assuranceEnforced);
    }

    private static AuthProperties build(
            URI issuer,
            Map<String, AuthProperties.LoginProviderSeed> providers,
            Duration stepUpMaxAge,
            List<String> browserExtensionIds,
            boolean assuranceEnforced) {
        return new AuthProperties(
                issuer,
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
                browserExtensionIds);
    }
}
