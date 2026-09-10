package de.tum.cit.aet.hephaestus.core.security;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Operator-approved OIDC issuers are exact identities, not host origins or interchangeable realms. */
@Component
@ConditionalOnServerRole
public class OidcIssuerPolicy {

    private final Set<String> allowedIssuers;

    public OidcIssuerPolicy(@Value("${hephaestus.auth.oidc.allowed-issuers:}") Set<String> allowedIssuers) {
        this.allowedIssuers = allowedIssuers.stream()
                .map(String::trim)
                .peek(OidcIssuerPolicy::validateUrl)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean allows(String issuer) {
        return allowedIssuers.contains(issuer);
    }

    /** HTTPS with a public host; paths are meaningful, but credentials, queries and fragments are not. */
    public static void validateUrl(String value) {
        URI uri = URI.create(value);
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("OIDC URLs must have a valid hostname");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("OIDC URLs must not contain credentials, a query or a fragment");
        }
        ServerUrlValidator.validate(uri.getScheme() + "://" + uri.getRawAuthority());
    }
}
