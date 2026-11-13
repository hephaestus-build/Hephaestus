package de.tum.cit.aet.hephaestus.integration.slack;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the server reaches the Slack API: one value for the OAuth client and for the Web API client that posts the
 * replies. The other {@code hephaestus.integration.slack} keys are read where they are used.
 *
 * <p>Only the server's environment sets the value. No chart value, database setting, request or workspace path does.
 * It is for tests: a server that another address replaces sends every workspace's bot token and all its Slack
 * traffic there.
 *
 * @param apiBase the origin of the Slack API: an absolute {@code http} or {@code https} URL with a host and without
 *     user information, query or fragment. A trailing slash is dropped.
 */
@ConfigurationProperties(prefix = "hephaestus.integration.slack")
public record SlackProperties(
        @DefaultValue(SlackProperties.SLACK) String apiBase) {
    /** The Slack API of every real installation. */
    public static final String SLACK = "https://slack.com";

    private static final Logger log = LoggerFactory.getLogger(SlackProperties.class);

    public SlackProperties {
        apiBase = validated(apiBase);
        if (!SLACK.equals(apiBase)) {
            // Only the origin: the path of a proxy can hold a token.
            log.warn(
                    "hephaestus.integration.slack.api-base is {}, not {}: every workspace's Slack bot token and all"
                            + " Slack traffic go to that host. Set it only in tests.",
                    origin(apiBase),
                    SLACK);
        }
    }

    /** The scheme, host and port of a validated base. */
    private static String origin(String apiBase) {
        URI uri = URI.create(apiBase);
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost()
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }

    /**
     * The parsed value, or empty when it is no URI. The syntax error is not kept: its message repeats the value, which
     * can carry user information.
     */
    private static Optional<URI> parsed(String value) {
        try {
            return Optional.of(new URI(value.strip()));
        } catch (URISyntaxException invalid) {
            return Optional.empty();
        }
    }

    private static String validated(String value) {
        URI uri = parsed(value).orElseThrow(SlackProperties::refused);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute()
                || !(scheme.equals("http") || scheme.equals("https"))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw refused();
        }
        String text = uri.toString();
        return text.endsWith("/") ? text.replaceAll("/+$", "") : text;
    }

    /** The message never repeats the value: it can carry user information. */
    private static IllegalArgumentException refused() {
        return new IllegalArgumentException(
                "hephaestus.integration.slack.api-base must be an absolute http or https URL with a host and without"
                        + " user information, query or fragment");
    }
}
