package de.tum.cit.aet.hephaestus.core.security;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.security.util.matcher.InetAddressMatcher;
import org.springframework.security.util.matcher.InetAddressMatchers;

/**
 * Private destinations that an operator lets an outbound client reach, as host names, IP addresses
 * or CIDR ranges.
 *
 * <p>A host name entry trusts that name's DNS, so it must be a name the operator controls. An address
 * entry admits any host name that resolves into the range. Either way, only an address in a private
 * range qualifies, and never a cloud metadata address inside one: loopback, link-local, NAT64 and
 * public addresses are out of reach of the list.
 */
public final class PrivateHostAllowlist {

    public static final PrivateHostAllowlist NONE = new PrivateHostAllowlist(Set.of(), List.of());

    /** RFC 1918, RFC 6598 shared address space, and IPv6 unique-local addresses. */
    private static final List<InetAddressMatcher> PRIVATE_RANGES =
            matchers(List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "fc00::/7"));

    /**
     * The instance metadata services that sit inside the private ranges: Alibaba Cloud's, AWS's IPv6 and
     * Google Cloud's IPv6 endpoint. Azure, Oracle Cloud and Hetzner Cloud document only 169.254.169.254,
     * which is link-local.
     */
    private static final List<InetAddressMatcher> METADATA =
            matchers(List.of("100.100.100.200", "fd00:ec2::254", "fd20:ce::254"));

    private final Set<String> hostNames;
    private final List<InetAddressMatcher> ranges;

    private PrivateHostAllowlist(Set<String> hostNames, List<InetAddressMatcher> ranges) {
        this.hostNames = hostNames;
        this.ranges = ranges;
    }

    /** @throws IllegalArgumentException for an entry that is no host name, IP address or CIDR range */
    public static PrivateHostAllowlist parse(Collection<String> entries) {
        Set<String> hostNames = new HashSet<>();
        List<String> ranges = new ArrayList<>();
        for (String raw : entries) {
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.contains("/") || entry.contains(":") || entry.matches("[0-9.]+")) {
                ranges.add(entry);
            } else if (entry.matches("[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)*")) {
                hostNames.add(entry);
            } else {
                throw new IllegalArgumentException("Not a host name, IP address or CIDR range: " + raw);
            }
        }
        return new PrivateHostAllowlist(Set.copyOf(hostNames), matchers(ranges));
    }

    /** True if {@code address} is private, and {@code host} or the address is listed. */
    public boolean permits(String host, InetAddress address) {
        if (!matchesAny(PRIVATE_RANGES, address) || matchesAny(METADATA, address)) {
            return false;
        }
        return hostNames.contains(host.toLowerCase(Locale.ROOT)) || matchesAny(ranges, address);
    }

    private static List<InetAddressMatcher> matchers(List<String> ranges) {
        return ranges.stream().map(InetAddressMatchers::fromIpAddress).toList();
    }

    private static boolean matchesAny(List<InetAddressMatcher> matchers, InetAddress address) {
        return matchers.stream().anyMatch(matcher -> matcher.matches(address));
    }
}
