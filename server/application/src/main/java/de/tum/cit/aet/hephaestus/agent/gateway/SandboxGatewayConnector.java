package de.tum.cit.aet.hephaestus.agent.gateway;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;

/**
 * The Tomcat connector that sandboxes reach the worker on, and the port it listens on. Port {@code 0} binds a free
 * port when the web server starts, as {@code server.port=0} does for the application connector; {@link #port()} then
 * reports the port that was bound.
 */
public final class SandboxGatewayConnector {
    private final Connector connector;
    private final int configuredPort;

    public SandboxGatewayConnector(int configuredPort) {
        this.configuredPort = configuredPort;
        this.connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        this.connector.setPort(configuredPort);
    }

    Connector connector() {
        return connector;
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
