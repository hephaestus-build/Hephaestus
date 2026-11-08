package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.config.ApplicationProperties;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import java.net.URI;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class EmailUnsubscribeLinks {
    private final ApplicationProperties properties;
    private final PublicApiUrls apiUrls;

    public EmailUnsubscribeLinks(ApplicationProperties properties, PublicApiUrls apiUrls) {
        this.properties = properties;
        this.apiUrls = apiUrls;
    }

    public String url(String token) {
        URI target = apiUrls.uri("/notifications/unsubscribe/{token}", token);
        return publicBase(target.toString()).build(true).toUriString();
    }

    public String confirmationUrl(String token) {
        return publicBase(properties.webapp().url())
                .pathSegment("unsubscribe")
                .queryParam("token", token)
                .build()
                .encode()
                .toUriString();
    }

    private static UriComponentsBuilder publicBase(String value) {
        URI uri = URI.create(value);
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (host == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || uri.getRawQuery() != null
                || !("https".equalsIgnoreCase(uri.getScheme())
                        || ("http".equalsIgnoreCase(uri.getScheme()) && loopback))) {
            throw new IllegalArgumentException(
                    "Public email URLs require HTTPS, except HTTP on a loopback development host, and no credentials, query or fragment");
        }
        return UriComponentsBuilder.fromUriString(value);
    }
}
