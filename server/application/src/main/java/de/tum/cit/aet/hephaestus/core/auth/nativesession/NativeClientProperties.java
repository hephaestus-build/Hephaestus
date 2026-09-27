package de.tum.cit.aet.hephaestus.core.auth.nativesession;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Native app sign-in configuration, bound to {@code hephaestus.auth.native}. Operator guidance:
 * {@code docs/admin/mobile-app.mdx}.
 *
 * @param redirectUris      the exact callback URIs a native sign-in may return to. Anything else is
 *                          refused before the identity provider is contacted; blank entries are ignored.
 * @param minimumAppVersion the oldest app version this server still serves, in {@code major.minor.patch};
 *                          blank means every version that speaks the current protocol
 */
@ConfigurationProperties(prefix = "hephaestus.auth.native")
public record NativeClientProperties(
        @DefaultValue("build.hephaestus.app:/auth/callback") List<String> redirectUris,
        @DefaultValue("") String minimumAppVersion) {
    public NativeClientProperties {
        redirectUris = redirectUris == null
                ? List.of()
                : redirectUris.stream()
                        .map(String::trim)
                        .filter(uri -> !uri.isEmpty())
                        .toList();
        minimumAppVersion = minimumAppVersion == null ? "" : minimumAppVersion.trim();
    }

    /** Whether {@code candidate} has the shape of an RFC 7636 S256 challenge. */
    public static boolean isPkceChallenge(String candidate) {
        return Pkce.isChallenge(candidate);
    }

    /** Exact string match: a native redirect is never prefix-, host- or pattern-matched (RFC 8252 §8.10). */
    public boolean allowsRedirect(String redirectUri) {
        return redirectUris.contains(redirectUri);
    }
}
