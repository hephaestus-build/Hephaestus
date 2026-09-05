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

    /** Production defaults (no proxy prefix, no seeded providers). */
    public static AuthProperties defaults() {
        return build("", Map.of(), Duration.ofMinutes(5));
    }

    /** Defaults with the given {@code apiBasePath} (the constructor normalizes it). */
    public static AuthProperties withApiBasePath(String apiBasePath) {
        return build(apiBasePath, Map.of(), Duration.ofMinutes(5));
    }

    /** Defaults with the given recent-sign-in window. */
    public static AuthProperties withStepUpMaxAge(Duration stepUpMaxAge) {
        return build("", Map.of(), stepUpMaxAge);
    }

    /** Defaults with the given seeded login providers. */
    public static AuthProperties withLoginProviders(Map<String, AuthProperties.LoginProviderSeed> loginProviders) {
        return build("", loginProviders, Duration.ofMinutes(5));
    }

    private static AuthProperties build(
            String apiBasePath, Map<String, AuthProperties.LoginProviderSeed> providers, Duration stepUpMaxAge) {
        return new AuthProperties(
                URI.create("http://localhost:8080"),
                apiBasePath,
                "hephaestus-spa",
                Duration.ofMinutes(15),
                AuthProperties.DEFAULT_COOKIE_NAME,
                "",
                Duration.ofHours(48),
                providers,
                List.of(),
                "",
                Duration.ofHours(1),
                Duration.ofHours(12),
                stepUpMaxAge,
                false,
                true);
    }
}
