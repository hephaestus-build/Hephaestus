package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.job.ReviewAttemptLease;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.DelegatingWebMvcConfiguration;

/** Runs the production transfer controllers for sandbox transport tests. */
public final class LiveSandboxGateway implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LiveSandboxGateway.class);
    private final SandboxGatewaySessions sessions = new SandboxGatewaySessions();
    private final Tomcat tomcat = new Tomcat();
    private final Path directory;

    /** On a free port, as the Docker tests use it. */
    public LiveSandboxGateway() throws Exception {
        this(0);
    }

    /**
     * On the given port, or a free one for {@code 0}. The connector binds every address of this host, so a sandbox
     * reaches it at whatever address its runtime gives it.
     */
    public LiveSandboxGateway(int port) throws Exception {
        directory = Files.createTempDirectory("live-sandbox-gateway-");
        tomcat.setBaseDir(directory.toString());
        tomcat.setPort(port);
        tomcat.getConnector();
        var context = tomcat.addContext("", directory.toString());
        var spring = new AnnotationConfigWebApplicationContext();
        spring.getEnvironment().setActiveProfiles("sandbox-live-fixture");
        spring.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("gatewaySessions", sessions));
        // Registered directly rather than through a nested @Configuration: the application's component
        // scan covers the test tree, and a scannable @EnableWebMvc would replace Boot's MVC setup in
        // every integration context.
        spring.register(
                DelegatingWebMvcConfiguration.class, SandboxWorkspaceController.class, ReviewLeaseFixture.class);
        context.addServletContainerInitializer(
                (classes, servletContext) -> {
                    var servlet = servletContext.addServlet("gateway", new DispatcherServlet(spring));
                    servlet.setLoadOnStartup(1);
                    servlet.addMapping("/");
                },
                null);
        tomcat.start();
        log.info("Live sandbox gateway listening on port {} of every address (requested port {})", port(), port);
    }

    public int port() {
        return tomcat.getConnector().getLocalPort();
    }

    public SandboxGatewaySessions sessions() {
        return sessions;
    }

    /** Kernel qualification has registered fixture sessions; the real row/lease fence is proved on PostgreSQL. */
    @RestController
    @Profile("sandbox-live-fixture")
    public static class ReviewLeaseFixture {
        private final SandboxGatewaySessions sessions;

        public ReviewLeaseFixture(SandboxGatewaySessions sessions) {
            this.sessions = sessions;
        }

        @PostMapping("/internal/llm/runtime/{id}/lease")
        public ReviewAttemptLease.LeaseReceiptDTO renew(
                @PathVariable UUID id, @RequestHeader("Authorization") String authorization) {
            sessions.require(id, authorization);
            return new ReviewAttemptLease.LeaseReceiptDTO(60_000, 20_000);
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            tomcat.stop();
            tomcat.destroy();
        } catch (LifecycleException exception) {
            failure = new IllegalStateException("Could not stop test gateway", exception);
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            FileSystemUtils.deleteRecursively(directory);
        } catch (IOException exception) {
            if (failure == null) {
                failure = new UncheckedIOException(exception);
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
