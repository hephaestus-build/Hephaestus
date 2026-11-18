package de.tum.cit.aet.hephaestus.agent.gateway;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.tomcat.util.threads.VirtualThreadExecutor;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;

/**
 * The Tomcat connector that sandboxes reach the server on, and the port it listens on. Port {@code 0} binds a free
 * port when the web server starts, as {@code server.port=0} does for the application connector; {@link #port()} then
 * reports the port that was bound.
 *
 * <p>Boot's connector customizers do not reach an additional connector, so this one states its own settings. A model
 * call holds its request thread until its stream ends, so a platform pool would bound the calls of all attempts on
 * this server. Virtual threads leave {@code maxConnections} and the gateway's body budget as the bounds. A blocking
 * socket read or write unmounts its virtual thread, also inside a monitor since JEP 491, so the gateway needs no carrier
 * per connection.
 */
public final class SandboxGatewayConnector {

    /** The request attribute that holds the address of the TCP peer, which no header can change. */
    public static final String PEER_ATTRIBUTE = "de.tum.cit.aet.hephaestus.sandbox-gateway.peer";

    /** How long a caller may stay silent, on a new connection, between reads, or while it reads a response. */
    private static final int SILENCE_MILLIS = 30_000;

    /**
     * The largest request header block, as {@code server.max-http-request-header-size} sets it for the application
     * connector. Tomcat answers a larger one with 400 before the gateway's filters read the caller's token.
     */
    static final int MAX_REQUEST_HEADER_BYTES = 8 * 1024;

    private final Connector connector;
    private final AbstractHttp11Protocol<?> protocol;
    private final int configuredPort;

    public SandboxGatewayConnector(int configuredPort, int maxConnections) {
        this.configuredPort = configuredPort;
        this.connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        this.connector.setPort(configuredPort);
        if (!(connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> http11)) {
            throw new IllegalStateException("The sandbox gateway needs an HTTP/1.1 connector");
        }
        this.protocol = http11;
        protocol.setExecutor(new VirtualThreadExecutor("sandbox-gateway-"));
        protocol.setMaxConnections(maxConnections);
        protocol.setConnectionTimeout(SILENCE_MILLIS);
        protocol.setMaxHttpRequestHeaderSize(MAX_REQUEST_HEADER_BYTES);
    }

    Connector connector() {
        return connector;
    }

    /** The connections open now; a negative count before the web server starts. */
    long connections() {
        return protocol.getConnectionCount();
    }

    int maxConnections() {
        return protocol.getMaxConnections();
    }

    /**
     * The port sandboxes dial: the bound port once the web server listens, or the configured fixed port before.
     *
     * @throws IllegalStateException if the port is chosen at start and the web server has not bound it yet
     */
    public int port() {
        int bound = connector.getLocalPort();
        if (bound > 0) return bound;
        if (configuredPort > 0) return configuredPort;
        throw new IllegalStateException("The sandbox gateway has not bound its port yet");
    }
}
