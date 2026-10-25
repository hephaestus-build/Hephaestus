package de.tum.cit.aet.hephaestus.testconfig;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Host-name resolution for every test JVM (JEP 418): loopback names and the host's names for its own addresses resolve,
 * a name a test declared with {@link #answer} resolves to its declared address, a name reserved by RFC 2606 or RFC 6761
 * or made only of numbers fails as an unknown host, and any other name is refused and recorded, so no JVM client in a
 * test resolves a real host. A reverse lookup of a public address fails the same way, and is recorded unless one of the
 * host's own interfaces carries the address. {@link OfflineNetworkExtension} fails the test class that made an attempt.
 * The guard covers {@link InetAddress} lookups in the test JVM only: a child process such as {@code git}, a native DNS
 * client or a container uses its own resolver. The live tier sets {@value #OPEN} to {@code true} and resolves every
 * name.
 */
public final class OfflineNetwork extends InetAddressResolverProvider {

    public static final String OPEN = "hephaestus.test.network.open";

    /**
     * A public address, owned by IANA, for a declared name whose code classifies the address without connecting to
     * it: the private-address guard refuses every documentation and benchmark range.
     */
    public static final String PUBLIC_ADDRESS = "192.0.43.8";

    // A top-level label is never all-numeric (RFC 1123 § 2.1), so such a name is a malformed address, not a host.
    private static final Pattern NUMERIC = Pattern.compile("[0-9.]+");
    private static final Set<String> RESERVED_DOMAINS =
            Set.of("test", "example", "invalid", "localhost", "example.com", "example.net", "example.org");
    private static final List<String> ATTEMPTS = new CopyOnWriteArrayList<>();
    private static final Map<String, InetAddress> ANSWERS = new ConcurrentHashMap<>();

    /**
     * Declares what a name resolves to until the test class ends, for code that classifies an address: the URL checks
     * resolve a name to tell a public host from a private one. Call it from a {@code @BeforeAll} method. The name then
     * resolves like a real host, so the test must not connect to it.
     */
    public static void answer(String host, String address) {
        try {
            ANSWERS.put(
                    host.toLowerCase(Locale.ROOT),
                    InetAddress.getByAddress(
                            host, InetAddress.getByName(address).getAddress()));
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Not an IP address: " + address, e);
        }
    }

    /** Forgets every declared name; {@link OfflineNetworkExtension} calls it after each test class. */
    static void forgetAnswers() {
        ANSWERS.clear();
    }

    /** The real host names that tests asked for since the JVM started, in order. */
    public static List<String> attempts() {
        return List.copyOf(ATTEMPTS);
    }

    /** Forgets the attempts after the first {@code kept}, which the guard's own tests make on purpose. */
    static void forgetAttemptsAfter(int kept) {
        ATTEMPTS.subList(kept, ATTEMPTS.size()).clear();
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        String localHost = configuration.lookupLocalHostName().toLowerCase(Locale.ROOT);
        // The names the host gives its own addresses: the JDK checks a canonical name with a forward lookup.
        Set<String> ownNames = ConcurrentHashMap.newKeySet();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                String name = host.toLowerCase(Locale.ROOT);
                if (Boolean.getBoolean(OPEN) || name.equals("localhost") || name.equals(localHost))
                    return builtin.lookupByName(host, policy);
                if (ownNames.contains(name)) {
                    List<InetAddress> own = builtin.lookupByName(host, policy)
                            .filter(OfflineNetwork::ownInterface)
                            .toList();
                    if (!own.isEmpty()) return own.stream();
                }
                InetAddress answer = ANSWERS.get(name);
                if (answer != null) return Stream.of(answer);
                if (!reserved(name)) ATTEMPTS.add(name);
                throw new UnknownHostException(host + ": tests resolve no real hosts");
            }

            @Override
            public String lookupByAddress(byte[] address) throws UnknownHostException {
                InetAddress inet = InetAddress.getByAddress(address);
                if (Boolean.getBoolean(OPEN) || !isPublic(inet)) {
                    String name = builtin.lookupByAddress(address);
                    if (ownInterface(inet)) ownNames.add(name.toLowerCase(Locale.ROOT));
                    return name;
                }
                String reverse = inet.getHostAddress();
                if (!ownInterface(inet)) ATTEMPTS.add("reverse " + reverse);
                throw new UnknownHostException(reverse + ": tests resolve no real hosts");
            }
        };
    }

    @Override
    public String name() {
        return "hephaestus-offline-tests";
    }

    // Private networks answer from local resolvers; only a public address is a real host.
    private static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean uniqueLocal = bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
        return !(address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || uniqueLocal);
    }

    // A host without NAT carries a public address on its own interface, and mail code names the local host; its
    // name then falls back to the address, as when the host has no reverse entry.
    private static boolean ownInterface(InetAddress address) {
        try {
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (SocketException e) {
            return false;
        }
    }

    private static boolean reserved(String name) {
        return NUMERIC.matcher(name).matches()
                || RESERVED_DOMAINS.stream().anyMatch(domain -> name.equals(domain) || name.endsWith("." + domain));
    }
}
