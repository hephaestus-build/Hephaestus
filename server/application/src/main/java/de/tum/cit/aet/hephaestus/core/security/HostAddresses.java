package de.tum.cit.aet.hephaestus.core.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The one place that turns text into an {@link InetAddress}. {@link #literal} parses an IP literal and never looks a
 * name up. {@link #resolve} asks DNS, so it never runs in a transaction: a slow resolver would hold a pooled
 * connection. {@code InetAddress.getByName} parses a literal but resolves anything else, and a string of hex digits,
 * such as {@code cafe}, is a name.
 */
public final class HostAddresses {

    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";

    /** Four decimal octets without leading zeros: the only IPv4 form that names the same address everywhere. */
    private static final Pattern IPV4 = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");

    /**
     * Starts with a hex digit or a colon, holds a colon, then hex digits, colons and an embedded IPv4 tail, with an
     * optional zone id. The JDK parses a text as a literal only when it starts with a hex digit or a colon; anything
     * else, such as {@code .:1}, it looks up as a name.
     */
    private static final Pattern IPV6 = Pattern.compile("(?=[^%]*:)[0-9A-Fa-f:][0-9A-Fa-f:.]*(%[0-9A-Za-z_.\\-]+)?");

    private HostAddresses() {}

    /** The address that a canonical dotted-quad or an IPv6 literal (without brackets) names, or empty. */
    public static Optional<InetAddress> literal(String text) {
        if (!IPV4.matcher(text).matches() && !IPV6.matcher(text).matches()) return Optional.empty();
        try {
            return Optional.of(InetAddress.getByName(text));
        } catch (UnknownHostException malformed) {
            return Optional.empty();
        }
    }

    /** Every address that DNS gives the host now. Call it outside a transaction. */
    public static InetAddress[] resolve(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }
}
