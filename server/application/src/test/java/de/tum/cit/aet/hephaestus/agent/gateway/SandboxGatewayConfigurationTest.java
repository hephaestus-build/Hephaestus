package de.tum.cit.aet.hephaestus.agent.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.catalina.valves.RemoteIpValve;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;

class SandboxGatewayConfigurationTest extends BaseUnitTest {

    private static final byte[] HELD_CALL =
            "GET / HTTP/1.1\r\nHost: gateway\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    /**
     * The security-relevant half of the change: sandboxes dial the worker across a per-job Docker
     * bridge, so the gateway must answer on every interface, while the connector serving {@code
     * /api/**} and the management endpoints stays wherever {@code server.address} put it.
     */
    @Test
    void bindsTheGatewayOnEveryInterfaceWhileTheApplicationConnectorStaysWhereServerAddressPutIt() {
        var factory = new TomcatServletWebServerFactory();
        factory.setAddress(InetAddress.getLoopbackAddress());

        var configuration = new SandboxGatewayConfiguration();
        configuration
                .sandboxGatewayConnectorCustomizer(
                        configuration.sandboxGatewayConnector(new SandboxGatewayProperties(9081, 1024, 60, 1000, 4096)))
                .customize(factory);

        assertThat(factory.getAdditionalConnectors()).singleElement().satisfies(connector -> {
            assertThat(connector.getPort()).isEqualTo(9081);
            assertThat(connector.getProperty("address")).isNull();
        });
        assertThat(factory.getAddress()).isEqualTo(InetAddress.getLoopbackAddress());
    }

    @Test
    void shouldReportTheBoundPortWhenTheGatewayBindsAFreePort() throws IOException {
        var factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(InetAddress.getLoopbackAddress());
        var configuration = new SandboxGatewayConfiguration();
        var gateway = configuration.sandboxGatewayConnector(new SandboxGatewayProperties(0, 1024, 60, 1000, 4096));
        configuration.sandboxGatewayConnectorCustomizer(gateway).customize(factory);

        assertThatThrownBy(gateway::port).isInstanceOf(IllegalStateException.class);
        var server = factory.getWebServer();
        server.start();
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), gateway.port())) {
            assertThat(socket.isConnected()).isTrue();
            assertThat(gateway.port()).isNotEqualTo(server.getPort());
        } finally {
            server.stop();
        }
    }

    /**
     * A model call holds its request thread until its stream ends. The connection count here is above the 200 threads
     * of Tomcat's own pool, so a gateway on that pool would never see all of them.
     */
    @Test
    @Timeout(60)
    void shouldServeMoreHeldCallsThanAPlatformPoolHoldsAndReportTheirConnections() throws Exception {
        int calls = 250;
        var factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(InetAddress.getLoopbackAddress());
        var configuration = new SandboxGatewayConfiguration();
        var gateway = configuration.sandboxGatewayConnector(new SandboxGatewayProperties(0, 1024, 60, 1000, 512));
        configuration.sandboxGatewayConnectorCustomizer(gateway).customize(factory);
        var meters = new SimpleMeterRegistry();
        configuration.sandboxGatewayConnectionMeters(gateway).bindTo(meters);

        var held = new CountDownLatch(calls);
        var release = new CountDownLatch(1);
        var onVirtualThreads = new AtomicInteger();
        HttpServlet heldCall = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) {
                if (Thread.currentThread().isVirtual()) onVirtualThreads.incrementAndGet();
                held.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            }
        };
        var server = factory.getWebServer(
                context -> context.addServlet("held", heldCall).addMapping("/*"));
        server.start();
        var sockets = new ArrayList<Socket>();
        try {
            for (int i = 0; i < calls; i++) {
                var socket = new Socket(InetAddress.getLoopbackAddress(), gateway.port());
                socket.getOutputStream().write(HELD_CALL);
                sockets.add(socket);
            }

            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(onVirtualThreads).hasValue(calls);
            assertThat(meters.get(AgentMetrics.SANDBOX_GATEWAY_CONNECTIONS)
                            .gauge()
                            .value())
                    .isGreaterThanOrEqualTo(calls);
            assertThat(meters.get(AgentMetrics.SANDBOX_GATEWAY_CONNECTIONS_LIMIT)
                            .gauge()
                            .value())
                    .isEqualTo(512);
        } finally {
            release.countDown();
            for (var socket : sockets) socket.close();
            server.stop();
        }
    }

    /**
     * The documented trusted proxies match the Pod network, so a sandbox counts as one, and the forwarded header moves
     * {@code getRemoteAddr()}. The source limit must see the connection, whatever the header says.
     */
    @Test
    void shouldHandTheFiltersThePeerAddressWhenACallerForgesAForwardedAddress() throws Exception {
        var factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(InetAddress.getLoopbackAddress());
        var trustedPodNetwork = new RemoteIpValve();
        trustedPodNetwork.setInternalProxies(".*");
        trustedPodNetwork.setRemoteIpHeader("X-Forwarded-For");
        factory.addEngineValves(trustedPodNetwork);
        var configuration = new SandboxGatewayConfiguration();
        var gateway = configuration.sandboxGatewayConnector(new SandboxGatewayProperties(0, 1024, 60, 1000, 4096));
        configuration.sandboxGatewayConnectorCustomizer(gateway).customize(factory);
        var seen = new CompletableFuture<List<@Nullable Object>>();
        HttpServlet observed = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) {
                seen.complete(Arrays.asList(
                        request.getAttribute(SandboxGatewayConnector.PEER_ATTRIBUTE), request.getRemoteAddr()));
            }
        };
        var server = factory.getWebServer(
                context -> context.addServlet("observed", observed).addMapping("/*"));
        server.start();
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), gateway.port())) {
            socket.getOutputStream()
                    .write("GET / HTTP/1.1\r\nHost: gateway\r\nX-Forwarded-For: 203.0.113.9\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));

            assertThat(seen.get(30, TimeUnit.SECONDS))
                    .containsExactly(InetAddress.getLoopbackAddress().getHostAddress(), "203.0.113.9");
        } finally {
            server.stop();
        }
    }
}
