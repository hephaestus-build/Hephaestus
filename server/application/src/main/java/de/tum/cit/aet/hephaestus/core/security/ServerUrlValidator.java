package de.tum.cit.aet.hephaestus.core.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Validates user-provided server URLs to prevent SSRF attacks.
 *
 * <p>When users supply a custom GitLab server URL, the backend makes HTTP requests
 * to that URL. This validator ensures the URL is safe before any outbound call.
 *
 * <h2>Blocked Patterns</h2>
 * <ul>
 *   <li>Non-HTTPS schemes (http, file, ftp, gopher, etc.)</li>
 *   <li>URLs with embedded credentials ({@code https://evil@legit.com})</li>
 *   <li>Localhost and loopback addresses (by hostname and IP)</li>
 *   <li>Cloud metadata endpoints (169.254.169.254, metadata.google.internal)</li>
 *   <li>Private/reserved TLDs (.internal, .local, .localhost)</li>
 *   <li>IP addresses that parse to loopback/link-local/site-local ranges</li>
 * </ul>
 *
 * <p><b>Note:</b> DNS resolution is intentionally NOT performed in this validator to keep it
 * pure (no I/O, no network). DNS-level SSRF protection (resolving hostnames to IPs and checking
 * ranges) should be handled at the HTTP client layer if needed.
 */
public final class ServerUrlValidator {

    private ServerUrlValidator() {}

    private static final Set<String> BLOCKED_HOSTNAMES =
            Set.of("localhost", "metadata.google.internal", "metadata.goog");

    private static final Set<String> BLOCKED_IPS =
            Set.of("127.0.0.1", "0.0.0.0", "::1", "0:0:0:0:0:0:0:1", "169.254.169.254");

    private static final Set<String> BLOCKED_TLDS = Set.of(".internal", ".local", ".localhost");

    /**
     * Validates a server URL for safety.
     *
     * @param url the URL to validate
     * @throws IllegalArgumentException if the URL is unsafe or malformed
     */
    public static void validate(@Nullable String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Server URL must not be blank");
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Server URL is malformed: " + e.getMessage(), e);
        }

        // Must be HTTPS
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Server URL must use HTTPS scheme, got: " + uri.getScheme());
        }

        // Must not contain userinfo (credentials in URL)
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Server URL must not contain embedded credentials");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Server URL must have a valid hostname");
        }

        String hostLower = host.toLowerCase(Locale.ROOT);

        // Block known dangerous hostnames
        if (BLOCKED_HOSTNAMES.contains(hostLower)) {
            throw new IllegalArgumentException("Server URL hostname is not allowed: " + host);
        }

        // Block known dangerous IPs
        if (BLOCKED_IPS.contains(hostLower)) {
            throw new IllegalArgumentException("Server URL must not point to a loopback or reserved address");
        }

        // Block private TLDs
        for (String tld : BLOCKED_TLDS) {
            if (hostLower.endsWith(tld)) {
                throw new IllegalArgumentException("Server URL must not use a private TLD: " + tld);
            }
        }

        // Must not have a path beyond / (prevents open-redirect abuse like https://legit.com/redirect)
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalArgumentException("Server URL must be a base URL without path segments");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Server URL must not contain a query or fragment");
        }

        // If the hostname looks like an IP address, validate its range
        validateIpRangeIfApplicable(host);
    }

    /**
     * If the hostname parses as an IP address, reject loopback/link-local/site-local/wildcard ranges.
     * This catches direct IP-based SSRF like {@code https://10.0.0.1} or {@code https://[fe80::1]}.
     */
    private static void validateIpRangeIfApplicable(String host) {
        // Strip IPv6 brackets if present
        String ipCandidate = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;

        // Reject non-canonical numeric IPv4 encodings (decimal-integer "2130706433", hex "0x7f000001",
        // octal/dotted-shorthand "0177.0.0.1"/"127.1") outright. InetAddress.getByName resolves these to
        // real private/loopback/metadata addresses (2130706433 == 127.0.0.1, 2852039166 == 169.254.169.254)
        // but their textual form never equals the canonical getHostAddress() string — the classic SSRF
        // deny-list bypass. A legitimate server base URL only ever uses a canonical dotted-quad or IPv6.
        if (isNumericIpv4Form(ipCandidate) && !isCanonicalDottedQuad(ipCandidate)) {
            throw new IllegalArgumentException("Server URL must not use a non-canonical numeric host: " + host);
        }

        InetAddress addr;
        try {
            // InetAddress.getByName() parses numeric IPs without DNS lookup
            addr = InetAddress.getByName(ipCandidate);
        } catch (UnknownHostException e) {
            // Not a parseable IP — it's a hostname, which is fine (DNS check is caller's concern)
            return;
        }

        // Only range-check IP literals (canonical dotted-quad or bracketed IPv6); a DNS hostname's resolved
        // address varies over time and is the HTTP client's concern. Non-canonical numeric forms are already
        // rejected above, so this equality check is now exact for the literals that reach it.
        if (!addr.getHostAddress().equalsIgnoreCase(ipCandidate) && !host.startsWith("[")) {
            return;
        }

        if (addr.isLoopbackAddress()) {
            throw new IllegalArgumentException("Server URL must not point to a loopback address");
        }
        if (addr.isLinkLocalAddress()) {
            throw new IllegalArgumentException("Server URL must not point to a link-local address");
        }
        if (addr.isSiteLocalAddress()) {
            throw new IllegalArgumentException("Server URL must not point to a private/site-local address");
        }
        if (addr.isAnyLocalAddress()) {
            throw new IllegalArgumentException("Server URL must not point to a wildcard address");
        }
        // IPv6 Unique-Local Addresses (fc00::/7) are private — the dominant self-hosted IPv6 range — but
        // Java has no isUniqueLocalAddress() and isSiteLocalAddress() returns false for them, so check the
        // high 7 bits directly. Without this, https://[fc00::1] / https://[fd12::1] would pass the gate.
        if (addr instanceof Inet6Address && (addr.getAddress()[0] & 0xFE) == 0xFC) {
            throw new IllegalArgumentException("Server URL must not point to a unique-local (ULA) address");
        }
    }

    /** True if the host is an all-numeric IPv4-style literal in any encoding (decimal, hex, dotted). */
    private static boolean isNumericIpv4Form(String host) {
        return host.matches("[0-9.]+") || host.matches("0[xX][0-9a-fA-F]+");
    }

    /** True only for a canonical dotted-quad IPv4 — exactly four 0-255 octets with no leading zeros. */
    private static boolean isCanonicalDottedQuad(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                return false;
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return false; // leading zero ⇒ octal ambiguity
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }
}
