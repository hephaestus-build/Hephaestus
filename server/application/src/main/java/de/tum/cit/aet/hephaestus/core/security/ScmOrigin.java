package de.tum.cit.aet.hephaestus.core.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The origin two server URLs are compared by: lower-case scheme and host, one spelling per IPv6 address, and
 * a port only when it is not the scheme's default. So {@code HTTPS://GitLab.example.com:443/} matches
 * {@code https://gitlab.example.com}, and {@code [2606:4700:4700::1111]} matches
 * {@code [2606:4700:4700:0:0:0:0:1111]}, as they do for a browser's URL origin. Paths, queries and fragments
 * are dropped, so reject them where a URL enters, not here. A comparison key only: persisted provider rows
 * keep the spelling they were created with.
 */
public final class ScmOrigin {

    private ScmOrigin() {}

    /** The origin of {@code url}, or empty when it is blank, malformed, or has no comparable scheme and host. */
    public static Optional<String> of(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(url.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return Optional.empty();
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            boolean defaultPort =
                    port == -1 || (port == 443 && "https".equals(scheme)) || (port == 80 && "http".equals(scheme));
            return host(uri.getHost()).map(host -> scheme + "://" + host + (defaultPort ? "" : ":" + port));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> host(String host) {
        if (!host.startsWith("[")) {
            return Optional.of(host.toLowerCase(Locale.ROOT));
        }
        String literal = host.substring(1, host.length() - 1);
        if (literal.contains("%")) {
            return Optional.empty();
        }
        try {
            // A literal containing ':' is parsed, never looked up. An IPv4-mapped address comes back as IPv4,
            // which a browser does not do, so it has no origin; nor does a zone id, which a browser rejects.
            return InetAddress.getByName(literal) instanceof Inet6Address address
                    ? Optional.of("[" + address.getHostAddress() + "]")
                    : Optional.empty();
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }
}
