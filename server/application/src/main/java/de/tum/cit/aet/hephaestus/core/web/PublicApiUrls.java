package de.tum.cit.aet.hephaestus.core.web;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import java.net.URI;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Public API addresses use the configured origin and proxy prefix, independent of request headers. */
@Component
public class PublicApiUrls {
    private final String baseUrl;

    public PublicApiUrls(AuthProperties authProperties) {
        this.baseUrl = authProperties.apiBaseUrl();
    }

    public URI uri(String pathTemplate, Object... variables) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .path(pathTemplate)
                .encode()
                .buildAndExpand(variables)
                .toUri();
    }
}
