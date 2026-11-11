package de.tum.cit.aet.hephaestus.core.web;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import java.net.URI;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Public endpoint addresses use the configured API origin, independent of request headers. */
@Component
public class PublicApiUrls {
    private final String baseUrl;
    private final String originUrl;

    public PublicApiUrls(AuthProperties authProperties) {
        this.baseUrl = authProperties.apiBaseUrl();
        this.originUrl = UriComponentsBuilder.fromUri(authProperties.issuer())
                .replacePath(null)
                .build()
                .toUriString();
    }

    public URI uri(String pathTemplate, Object... variables) {
        return build(baseUrl, pathTemplate, variables);
    }

    /** Receiver routes use the origin without the API proxy prefix. */
    public URI originUri(String pathTemplate, Object... variables) {
        return build(originUrl, pathTemplate, variables);
    }

    private static URI build(String base, String pathTemplate, Object... variables) {
        return UriComponentsBuilder.fromUriString(base)
                .path(pathTemplate)
                .encode()
                .buildAndExpand(variables)
                .toUri();
    }
}
