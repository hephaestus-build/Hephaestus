package de.tum.cit.aet.hephaestus.core;

import de.tum.cit.aet.hephaestus.core.security.PrivateAddressGuard;
import de.tum.cit.aet.hephaestus.core.security.PrivateHostAllowlist;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A Netty {@link AddressResolverGroup} that rejects DNS answers resolving to a non-public address,
 * closing the DNS-rebind / TOCTOU SSRF window at the HTTP-client layer (OWASP SSRF Prevention Cheat
 * Sheet recommends exactly this: do the IP check where the connection is actually made).
 *
 * <p>Validating only the URL string and then letting the client re-resolve DNS at connect time is the
 * classic bypass: an attacker host with a low TTL answers with a public IP during validation and flips
 * to {@code 169.254.169.254} / {@code 127.0.0.1} for the real connection. Here the check runs on the
 * SAME resolution the client uses to connect (we wrap the resolver and validate its output before
 * handing it back), so there is no second lookup to race — a non-public answer fails the resolution and
 * the connection never opens.
 *
 * <p>This enforces, for hostnames, the very policy {@code core.security.ServerUrlValidator} already
 * enforces for literal IPs in user-supplied server URLs — it does not add a new restriction, it stops a
 * bypass of the existing one. Delegates to {@link DefaultAddressResolverGroup} (the JVM/system resolver,
 * matching {@link WebClientConnectors#systemDns()}).
 *
 * <p>An operator's {@link PrivateHostAllowlist} admits a private answer only for a listed host name or
 * a listed range, so a rebind of any other name to a private address still fails.
 */
public final class SsrfGuardedResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    public static final SsrfGuardedResolverGroup INSTANCE =
            new SsrfGuardedResolverGroup(false, PrivateHostAllowlist.NONE);

    /**
     * Lets a RESOLVED loopback address through while still blocking every other private/reserved range,
     * so DNS rebinding to a non-loopback private address stays closed exactly as in {@link #INSTANCE}.
     * Only for callers whose policy layer has already allowed loopback as a dev/e2e target.
     */
    public static final SsrfGuardedResolverGroup LOOPBACK_EXEMPT_INSTANCE =
            new SsrfGuardedResolverGroup(true, PrivateHostAllowlist.NONE);

    private final boolean allowLoopback;
    private final PrivateHostAllowlist privateHosts;

    public SsrfGuardedResolverGroup(boolean allowLoopback, PrivateHostAllowlist privateHosts) {
        this.allowLoopback = allowLoopback;
        this.privateHosts = privateHosts;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        return new GuardedResolver(
                executor, DefaultAddressResolverGroup.INSTANCE.getResolver(executor), allowLoopback, privateHosts);
    }

    /**
     * Reject the resolution if the resolved address is non-public. Package-private + returning the
     * {@link UnknownHostException} (rather than throwing) so it is unit-testable with a stub delegate.
     */
    static @Nullable UnknownHostException blockedReason(InetSocketAddress resolved) {
        return blockedReason("", resolved, false, PrivateHostAllowlist.NONE);
    }

    static @Nullable UnknownHostException blockedReason(
            String host, InetSocketAddress resolved, boolean allowLoopback, PrivateHostAllowlist privateHosts) {
        InetAddress addr = (resolved == null) ? null : resolved.getAddress();
        if (addr == null) {
            return new UnknownHostException("address did not resolve");
        }
        if (allowLoopback && addr.isLoopbackAddress()) {
            return null;
        }
        if (PrivateAddressGuard.isNonPublic(addr) && !privateHosts.permits(host, addr)) {
            return new UnknownHostException("blocked non-public address (SSRF guard): " + addr.getHostAddress());
        }
        return null;
    }

    /** Wraps a delegate resolver and validates every resolved address before completing the promise. */
    static final class GuardedResolver implements AddressResolver<InetSocketAddress> {

        private final EventExecutor executor;
        private final AddressResolver<InetSocketAddress> delegate;
        private final boolean allowLoopback;
        private final PrivateHostAllowlist privateHosts;

        GuardedResolver(EventExecutor executor, AddressResolver<InetSocketAddress> delegate) {
            this(executor, delegate, false, PrivateHostAllowlist.NONE);
        }

        GuardedResolver(
                EventExecutor executor,
                AddressResolver<InetSocketAddress> delegate,
                boolean allowLoopback,
                PrivateHostAllowlist privateHosts) {
            this.executor = executor;
            this.delegate = delegate;
            this.allowLoopback = allowLoopback;
            this.privateHosts = privateHosts;
        }

        @Override
        public boolean isSupported(SocketAddress address) {
            return delegate.isSupported(address);
        }

        @Override
        public boolean isResolved(SocketAddress address) {
            return delegate.isResolved(address);
        }

        // CRITICAL on the 2-arg overloads: resolve the delegate into its OWN (1-arg) future and gate the
        // result into the caller's promise. We must NOT call delegate.resolve(address, callerPromise) —
        // that completes the caller's promise with the UNVALIDATED address (the caller reads the promise,
        // not our return value), which is an SSRF bypass. The 1-arg overloads gate into a fresh promise.

        @Override
        public Future<InetSocketAddress> resolve(SocketAddress address) {
            return gateOne(hostOf(address), delegate.resolve(address), executor.newPromise());
        }

        @Override
        public Future<InetSocketAddress> resolve(SocketAddress address, Promise<InetSocketAddress> promise) {
            return gateOne(hostOf(address), delegate.resolve(address), promise);
        }

        @Override
        public Future<List<InetSocketAddress>> resolveAll(SocketAddress address) {
            return gateAll(hostOf(address), delegate.resolveAll(address), executor.newPromise());
        }

        @Override
        public Future<List<InetSocketAddress>> resolveAll(
                SocketAddress address, Promise<List<InetSocketAddress>> promise) {
            return gateAll(hostOf(address), delegate.resolveAll(address), promise);
        }

        /** The name the caller asked for, which a host name entry of the allow-list matches. */
        private static String hostOf(SocketAddress address) {
            return address instanceof InetSocketAddress socket ? socket.getHostString() : "";
        }

        private Future<InetSocketAddress> gateOne(
                String host, Future<InetSocketAddress> source, Promise<InetSocketAddress> target) {
            source.addListener((FutureListener<InetSocketAddress>) f -> {
                if (!f.isSuccess()) {
                    target.setFailure(f.cause());
                    return;
                }
                UnknownHostException blocked = blockedReason(host, f.getNow(), allowLoopback, privateHosts);
                if (blocked != null) {
                    target.setFailure(blocked);
                } else {
                    target.setSuccess(f.getNow());
                }
            });
            return target;
        }

        private Future<List<InetSocketAddress>> gateAll(
                String host, Future<List<InetSocketAddress>> source, Promise<List<InetSocketAddress>> target) {
            source.addListener((FutureListener<List<InetSocketAddress>>) f -> {
                if (!f.isSuccess()) {
                    target.setFailure(f.cause());
                    return;
                }
                for (InetSocketAddress isa : f.getNow()) {
                    UnknownHostException blocked = blockedReason(host, isa, allowLoopback, privateHosts);
                    if (blocked != null) {
                        target.setFailure(blocked);
                        return;
                    }
                }
                target.setSuccess(f.getNow());
            });
            return target;
        }

        @Override
        public void close() {
            // Deliberately empty: the delegate belongs to the shared DefaultAddressResolverGroup
            // singleton, so closing it here would yank a process-wide resolver.
        }
    }
}
