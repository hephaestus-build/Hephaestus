package de.tum.cit.aet.hephaestus.core.auth.provider;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.OidcIssuerPolicy;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestOperations;

/** Fetches metadata only when an approved organizational provider is used, never during application startup. */
@Component
@ConditionalOnServerRole
public class OidcProviderDiscovery {

    private final OidcIssuerPolicy issuerPolicy;
    private final RestOperations restOperations;

    public OidcProviderDiscovery(
            OidcIssuerPolicy issuerPolicy, @Qualifier("oidcRestOperations") RestOperations restOperations) {
        this.issuerPolicy = issuerPolicy;
        this.restOperations = restOperations;
    }

    public ClientRegistration.Builder discover(String issuer) {
        if (!issuerPolicy.allows(issuer)) {
            throw new IllegalArgumentException("OIDC issuer is not approved by the instance operator");
        }
        // Discovery removes a trailing slash to append its well-known path; the issuer itself stays exact.
        String discoveryUrl = issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration";
        Map<String, Object> metadata = restOperations
                .exchange(
                        URI.create(discoveryUrl),
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<Map<String, Object>>() {})
                .getBody();
        if (metadata == null || !issuer.equals(metadata.get("issuer"))) {
            throw new IllegalArgumentException("OIDC discovery issuer does not match the configured issuer");
        }
        for (String endpoint : new String[] {"authorization_endpoint", "token_endpoint", "jwks_uri"}) {
            validateEndpoint(metadata, endpoint);
        }
        if (metadata.containsKey("userinfo_endpoint")) {
            validateEndpoint(metadata, "userinfo_endpoint");
        }
        // Spring validates the OIDC metadata and constructs the registration. The approved issuer is
        // authoritative for endpoint locations; DNS is guarded at connection time, including for JWKS.
        return ClientRegistrations.fromOidcConfiguration(metadata).userNameAttributeName("sub");
    }

    private static void validateEndpoint(Map<String, Object> metadata, String name) {
        if (!(metadata.get(name) instanceof String endpoint) || endpoint.isBlank()) {
            throw new IllegalArgumentException("OIDC discovery is missing " + name);
        }
        OidcIssuerPolicy.validateUrl(endpoint);
    }
}
