package de.tum.cit.aet.hephaestus.core.security;

import de.tum.cit.aet.hephaestus.core.SsrfGuardedResolverGroup;
import io.netty.resolver.AddressResolverGroup;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/** SCM endpoint validation shared by workspace input and outbound preflight requests. */
@Component
public class ScmServerEndpointPolicy {
    private final String simulationOrigin;

    private final WebClient.Builder webClients;
    private final PrivateHostAllowlist privateHosts;
    private final SsrfGuardedResolverGroup resolverGroup;

    public ScmServerEndpointPolicy(Environment environment, WebClient.Builder webClients, ScmEgressProperties egress) {
        this.webClients = webClients;
        this.privateHosts = PrivateHostAllowlist.parse(egress.privateHosts(), PrivateHostAllowlist.ownAddresses());
        simulationOrigin = environment.getProperty("hephaestus.e2e.scm-origin", "");
        // The simulator's loopback exemption covers its own port only.
        resolverGroup = new SsrfGuardedResolverGroup(
                !simulationOrigin.isEmpty(),
                simulationOrigin.isEmpty() ? 0 : URI.create(simulationOrigin).getPort(),
                privateHosts);
        if (!simulationOrigin.isEmpty()) {
            if (!environment.acceptsProfiles(Profiles.of("e2e & !prod"))) {
                throw new IllegalArgumentException("SCM simulation requires the e2e profile without prod");
            }
            URI uri = URI.create(simulationOrigin);
            if (!"http".equals(uri.getScheme())
                    || !"127.0.0.1".equals(uri.getHost())
                    || uri.getPort() < 1
                    || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null
                    || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalArgumentException(
                        "SCM simulation origin must be http://127.0.0.1:<port> without a trailing slash");
            }
        }
    }

    /**
     * The check on every connection to a Git provider. It reads the address that the connection is about to use, so a DNS
     * answer that changes after validation cannot reach a private, loopback or metadata address.
     */
    public AddressResolverGroup<InetSocketAddress> resolverGroup() {
        return resolverGroup;
    }

    /** A connector that applies {@link #resolverGroup()} and never follows a redirect. */
    public ClientHttpConnector connector() {
        return new ReactorClientHttpConnector(
                HttpClient.create().resolver(resolverGroup).followRedirect(false));
    }

    /**
     * Why a connection that is already open to {@code address} must not carry a request, or null when it may. For a
     * client that resolves without {@link #resolverGroup()}, read on the socket before the first byte is sent.
     */
    public @Nullable String refusal(String host, InetAddress address) {
        // Only the simulator is exempt, and it speaks plain HTTP, so a TLS peer never is.
        var blocked =
                SsrfGuardedResolverGroup.blockedReason(host, new InetSocketAddress(address, 443), false, privateHosts);
        return blocked == null ? null : blocked.getMessage();
    }

    public void validate(String serverUrl) {
        if (!isSimulation(serverUrl)) {
            ServerUrlValidator.validate(serverUrl, privateHosts);
        }
    }

    public WebClient clientFor(String serverUrl) {
        validate(serverUrl);
        URI origin = URI.create(serverUrl);
        return webClients
                .clone()
                .clientConnector(connector())
                .filter((request, next) -> {
                    URI target = request.url();
                    if (!origin.getScheme().equals(target.getScheme())
                            || !origin.getRawAuthority().equals(target.getRawAuthority())) {
                        return Mono.error(
                                new IllegalArgumentException("SCM request must stay on its validated origin"));
                    }
                    return next.exchange(request);
                })
                .build();
    }

    private boolean isSimulation(String serverUrl) {
        return !simulationOrigin.isEmpty()
                && (simulationOrigin.equals(serverUrl) || (simulationOrigin + "/").equals(serverUrl));
    }
}
