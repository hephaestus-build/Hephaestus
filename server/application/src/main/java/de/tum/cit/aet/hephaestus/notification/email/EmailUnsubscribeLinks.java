package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.config.ApplicationProperties;
import java.net.URI;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class EmailUnsubscribeLinks {
    private final ApplicationProperties properties;

    public EmailUnsubscribeLinks(ApplicationProperties properties) {
        this.properties = properties;
    }

    public String url(String token) {
        String host = properties.hostUrl();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("A public host URL is required for email unsubscribe links");
        }
        return publicBase(host)
                .pathSegment("notifications", "unsubscribe", token)
                .build()
                .encode()
                .toUriString();
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
