package de.tum.cit.aet.hephaestus.integration.core.oauth;

import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the OAuth callback ingress. Browsers get 302 redirects on both
 * success and failure; only {@code Accept: application/json} (curl / devtools) requests
 * receive 4xx JSON. {@code failureRedirect} defaults to the {@code successRedirect}
 * host with a {@code ?status=error} query when unset.
 */
@ConfigurationProperties(prefix = "hephaestus.integration.oauth")
public record OAuthCallbackProperties(
        String successRedirect, @Nullable String failureRedirect) {
    public OAuthCallbackProperties {
        if (successRedirect == null || successRedirect.isBlank()) {
            successRedirect = "/integrations?status=success";
        }
        if (failureRedirect != null && failureRedirect.isBlank()) {
            failureRedirect = null;
        }
    }

    /** The root of the application {@code successRedirect} points into, for a callback that connected nothing. */
    public String homeRedirect() {
        return URI.create(successRedirect).resolve("/").toString();
    }

    /**
     * Returns the failure-redirect base URL — explicit {@code failureRedirect} if set,
     * otherwise the {@code successRedirect}'s base with {@code ?status=error}.
     */
    public String resolvedFailureRedirect() {
        if (failureRedirect != null) {
            return failureRedirect;
        }
        int qmark = successRedirect.indexOf('?');
        String base = qmark < 0 ? successRedirect : successRedirect.substring(0, qmark);
        return base + "?status=error";
    }
}
