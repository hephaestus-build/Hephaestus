package de.tum.cit.aet.hephaestus.notification.push;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Push notification settings, bound to {@code hephaestus.push}. Operator guidance:
 * {@code docs/admin/mobile-app.mdx}.
 *
 * @param expoAccessToken the Expo access token of the project that published the app installations this
 *                        server reaches. Blank turns push off: devices cannot register and nothing is sent.
 * @param expoBaseUrl     the Expo push service origin; overridden only by tests
 */
@ConfigurationProperties(prefix = "hephaestus.push")
public record PushProperties(
        @DefaultValue("") String expoAccessToken,
        @DefaultValue("https://exp.host") URI expoBaseUrl) {
    public PushProperties {
        expoAccessToken = expoAccessToken == null ? "" : expoAccessToken.trim();
    }

    public boolean available() {
        return !expoAccessToken.isEmpty();
    }
}
