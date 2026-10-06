package de.tum.cit.aet.hephaestus.agent.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.AgentJobExecutor;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerCapacityState;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerControlClient;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerDrainCoordinator;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * A real web server with the production sandbox gateway connector and the real worker drain, shut down while a
 * review is still in flight. Nothing beyond the server and its two connectors is started.
 */
@Tag("integration")
class SandboxGatewayDrainIntegrationTest {

    private static final Duration DRAIN = Duration.ofSeconds(30);

    @Test
    @Timeout(90)
    void shouldKeepGatewayConnectorServingWhileDrainAwaitsInFlightReviewWhenContextCloses() throws Exception {
        WorkerProperties properties = WorkerPropertiesFixtures.withDrain(DRAIN);
        AgentJobExecutor executor = mock(AgentJobExecutor.class);
        CountDownLatch draining = new CountDownLatch(1);
        CountDownLatch reviewFinished = new CountDownLatch(1);
        when(executor.awaitInFlight(any(Duration.class))).thenAnswer(invocation -> {
            draining.countDown();
            return reviewFinished.await(20, TimeUnit.SECONDS);
        });
        var coordinator = new WorkerDrainCoordinator(
                mock(WorkerControlClient.class),
                new WorkerCapacityState(properties),
                properties,
                Optional.of(executor),
                Optional.empty(),
                mock(ApplicationEventPublisher.class),
                new SimpleMeterRegistry());

        var application = new SpringApplication(GatewayApplication.class);
        application.addInitializers(
                context -> context.getBeanFactory().registerSingleton("workerDrainCoordinator", coordinator));
        ConfigurableApplicationContext context = application.run(
                "--server.port=0",
                "--server.shutdown=graceful",
                "--spring.lifecycle.timeout-per-shutdown-phase=20s",
                "--spring.main.banner-mode=off");
        Thread closing = new Thread(context::close, "worker-drain-shutdown");
        closing.setDaemon(true);
        try {
            int gatewayPort = context.getBean(TomcatServletWebServerFactory.class).getAdditionalConnectors().stream()
                    .findFirst()
                    .orElseThrow()
                    .getLocalPort();
            closing.start();
            assertThat(draining.await(20, TimeUnit.SECONDS))
                    .as("the drain is waiting for the in-flight review")
                    .isTrue();

            // The review's next model request: a new connection to the gateway while the drain waits.
            try (var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build()) {
                var response = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + "/"))
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
                assertThat(response.statusCode()).isEqualTo(HttpServletResponse.SC_NO_CONTENT);
            }

            reviewFinished.countDown();
            assertThat(closing.join(Duration.ofSeconds(30)))
                    .as("the context finishes closing once the review is done")
                    .isTrue();
            assertThat(context.isActive()).isFalse();
            var order = inOrder(executor);
            order.verify(executor).stopAcceptingNewJobs();
            order.verify(executor).awaitInFlight(DRAIN);
            verify(executor, never()).cancelInFlight(any());
        } finally {
            reviewFinished.countDown();
            if (closing.getState() != Thread.State.NEW) {
                closing.join(Duration.ofSeconds(30));
            }
            context.close();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration(TomcatServletWebServerAutoConfiguration.class)
    static class GatewayApplication {

        /** The production connector, on a port the operating system assigns. */
        @Bean
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> sandboxGatewayConnector() {
            return new SandboxGatewayConfiguration().sandboxGatewayConnector(new SandboxGatewayProperties(0, 1024, 60));
        }

        @Bean
        HttpServlet modelGateway() {
            return new NoContentServlet();
        }
    }

    /** Stands in for the gateway's model route: answering at all is what the test needs. */
    static class NoContentServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }
    }
}
