package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayWebSocketConfiguration;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxWorkspaceController;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.DelegatingWebMvcConfiguration;

/** Runs the production transfer controllers and WebSocket channel for Docker transport tests. */
public final class LiveSandboxGateway implements AutoCloseable {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LiveSandboxGateway.class);
    private final SandboxGatewaySessions sessions = new SandboxGatewaySessions();
    private final Tomcat tomcat = new Tomcat();
    private final org.testcontainers.containers.SocatContainer relay =
            new org.testcontainers.containers.SocatContainer();
    private final Path directory;

    public LiveSandboxGateway() throws Exception {
        directory = Files.createTempDirectory("live-sandbox-gateway-");
        tomcat.setBaseDir(directory.toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        var context = tomcat.addContext("", directory.toString());
        context.addServletContainerInitializer(new WsSci(), null);
        var spring = new AnnotationConfigWebApplicationContext();
        spring.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("gatewaySessions", sessions));
        // Registered directly rather than through a nested @Configuration: the application's component
        // scan covers the test tree, and a scannable @EnableWebMvc would replace Boot's MVC setup in
        // every integration context.
        spring.register(
                DelegatingWebMvcConfiguration.class,
                SandboxWorkspaceController.class,
                SandboxGatewayWebSocketConfiguration.class);
        context.addServletContainerInitializer(
                (classes, servletContext) -> {
                    var servlet = servletContext.addServlet("gateway", new DispatcherServlet(spring));
                    servlet.setLoadOnStartup(1);
                    servlet.addMapping("/");
                },
                null);
        tomcat.start();
        org.testcontainers.Testcontainers.exposeHostPorts(port());
        relay.withTarget(port(), "host.testcontainers.internal", port())
                .withAccessToHost(true)
                .start();
        log.info("Live sandbox gateway: http://host.testcontainers.internal:{}/internal/llm/runtime", port());
    }

    public int port() {
        return tomcat.getConnector().getLocalPort();
    }

    public String containerId() {
        return relay.getContainerId();
    }

    public SandboxGatewaySessions sessions() {
        return sessions;
    }

    @Override
    public void close() {
        try {
            relay.stop();
            tomcat.stop();
            tomcat.destroy();
        } catch (org.apache.catalina.LifecycleException exception) {
            throw new IllegalStateException("Could not stop test gateway", exception);
        } finally {
            try {
                FileSystemUtils.deleteRecursively(directory);
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }
    }
}
