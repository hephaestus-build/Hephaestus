package de.tum.cit.aet.hephaestus.core.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
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
 * entry admits any host name that resolves into the range. Only an address in a private range qualifies:
 * loopback, link-local (which holds 169.254.169.254 and every other IPv4 metadata service), NAT64 and
 * public addresses are out of reach of the list. Java hands an IPv4-mapped IPv6 answer over as the IPv4
 * address, so the same checks apply to it.
 *
 * <p>Cloud providers also put metadata services inside the private ranges, and no list of them is ever
 * complete. So a range entry may be no wider than one network a model server lives in (/16 for IPv4,
 * /48 for IPv6), and the known metadata ranges stay refused as a second layer.
 */
public final class PrivateHostAllowlist {

    public static final PrivateHostAllowlist NONE = new PrivateHostAllowlist(Set.of(), List.of());

    private static final int WIDEST_IPV4_PREFIX = 16;
    private static final int WIDEST_IPV6_PREFIX = 48;

    /** RFC 1918, RFC 6598 shared address space, and IPv6 unique-local addresses. */
    private static final List<Range> PRIVATE_RANGES =
            ranges(List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "fc00::/7"));

    /** Cloud metadata and credential services that sit inside the private ranges. */
    private static final List<Range> METADATA = ranges(List.of(
            // Alibaba Cloud: https://www.alibabacloud.com/help/en/ecs/user-guide/view-instance-metadata
            "100.100.100.200",
            // AWS service prefix: IMDS ::254, EKS Pod Identity ::23, DNS ::253, NTP ::123.
            // https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/instancedata-data-retrieval.html
            // https://docs.aws.amazon.com/eks/latest/userguide/pod-id-agent-setup.html
            "fd00:ec2::/32",
            // Google Cloud documents only fd20:ce::254, and allocates each VPC's ULA range as a /48 of
            // fd20::/20, so the /48 that holds it is Google's own.
            // https://cloud.google.com/compute/docs/metadata/querying-metadata
            "fd20:ce::/48",
            // Akamai (Linode) fd00:a9fe:a9fe::1: https://techdocs.akamai.com/cloud-computing/docs/metadata-service-api
            "fd00:a9fe::/32",
            // Scaleway fd00:42::42: https://docs.cloud-init.io/en/latest/reference/datasources/scaleway.html
            "fd00:42::/32",
            // Oracle Cloud fd00:c1::a9fe:a9fe:
            // https://github.com/canonical/cloud-init/blob/main/cloudinit/sources/DataSourceOracle.py
            "fd00:c1::/32"));

    private final Set<String> hostNames;
    private final List<Range> ranges;

    private PrivateHostAllowlist(Set<String> hostNames, List<Range> ranges) {
        this.hostNames = hostNames;
        this.ranges = ranges;
    }

    /**
     * @param ownAddresses the addresses of this process. A range that covers one of them would admit the
     *     deployment's own services, so it is refused.
     * @throws IllegalArgumentException for an entry that is no host name, IP address or CIDR range, or a
     *     range that is outside the private ranges, wider than one network, inside a metadata range, or
     *     covers one of {@code ownAddresses}
     */
    public static PrivateHostAllowlist parse(Collection<String> entries, Collection<InetAddress> ownAddresses) {
        Set<String> hostNames = new HashSet<>();
        List<Range> ranges = new ArrayList<>();
        for (String raw : entries) {
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.contains("/") || entry.contains(":") || entry.matches("[0-9.]+")) {
                ranges.add(admittedRange(entry, raw, ownAddresses));
            } else if (entry.matches("[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)*")) {
                hostNames.add(entry);
            } else {
                throw new IllegalArgumentException("Not a host name, IP address or CIDR range: " + raw);
            }
        }
        return new PrivateHostAllowlist(Set.copyOf(hostNames), List.copyOf(ranges));
    }

    private static Range admittedRange(String entry, String raw, Collection<InetAddress> ownAddresses) {
        // InetAddress reads "10.1" as 10.0.0.1, so a typo would name another host.
        if (!entry.contains(":") && !entry.matches("\\d{1,3}(\\.\\d{1,3}){3}(/\\d{1,2})?")) {
            throw new IllegalArgumentException("Write an IPv4 address with four parts: " + raw);
        }
        Range range = Range.parse(entry);
        if (PRIVATE_RANGES.stream().noneMatch(range::isInside)) {
            throw new IllegalArgumentException(
                    "Not inside 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 100.64.0.0/10 or fc00::/7: " + raw);
        }
        if (range.prefix() < (range.isIpv4() ? WIDEST_IPV4_PREFIX : WIDEST_IPV6_PREFIX)) {
            throw new IllegalArgumentException("Wider than /" + WIDEST_IPV4_PREFIX + " for IPv4 or /"
                    + WIDEST_IPV6_PREFIX + " for IPv6. List the network that the model server is in: " + raw);
        }
        if (METADATA.stream().anyMatch(range::isInside)) {
            throw new IllegalArgumentException(
                    "Inside a cloud metadata range, which stays refused, so it would never connect: " + raw);
        }
        for (InetAddress own : ownAddresses) {
            if (range.matches(own)) {
                throw new IllegalArgumentException("Covers this server's own address " + own.getHostAddress()
                        + ", so it would admit this deployment's own services. "
                        + "List the model host or a narrower range: " + raw);
            }
        }
        return range;
    }

    /** True if {@code address} is private, and {@code host} or the address is listed. */
    public boolean permits(String host, InetAddress address) {
        if (PRIVATE_RANGES.stream().noneMatch(range -> range.matches(address))
                || METADATA.stream().anyMatch(range -> range.matches(address))) {
            return false;
        }
        return hostNames.contains(host.toLowerCase(Locale.ROOT))
                || ranges.stream().anyMatch(range -> range.matches(address));
    }

    private static List<Range> ranges(List<String> cidrs) {
        return cidrs.stream().map(Range::parse).toList();
    }

    /** A CIDR range, or one address as the range of its own full length. */
    private record Range(InetAddressMatcher matcher, InetAddress network, int prefix) {

        static Range parse(String cidr) {
            // The matcher validates the literal, so the lookup below parses it without DNS.
            InetAddressMatcher matcher = InetAddressMatchers.fromIpAddress(cidr);
            int slash = cidr.indexOf('/');
            InetAddress network;
            try {
                network = InetAddress.getByName(slash < 0 ? cidr : cidr.substring(0, slash));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Not an IP address or CIDR range: " + cidr, e);
            }
            int prefix = slash < 0 ? network.getAddress().length * 8 : Integer.parseInt(cidr.substring(slash + 1));
            return new Range(matcher, network, prefix);
        }

        boolean isIpv4() {
            return network.getAddress().length == 4;
        }

        /**
         * Spring Security's matcher compares bytes without checking the address family: an IPv4 address
         * can match an IPv6 prefix, or overrun a long one. So the family is compared first.
         */
        boolean matches(InetAddress address) {
            return address.getAddress().length == network.getAddress().length && matcher.matches(address);
        }

        boolean isInside(Range outer) {
            return outer.matches(network) && prefix >= outer.prefix;
        }
    }
}
