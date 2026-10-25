package de.tum.cit.aet.hephaestus.agent.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitConfig;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitProperties;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.github.bucket4j.BucketConfiguration;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.core.io.ClassPathResource;

class SandboxGatewayConfigurationTest extends BaseUnitTest {

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
                        configuration.sandboxGatewayConnector(new SandboxGatewayProperties(9081, 1024, 60)))
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
        var gateway = configuration.sandboxGatewayConnector(new SandboxGatewayProperties(0, 1024, 60));
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

    @Test
    void shouldProvideGatewayBucketsWithoutAuthInfrastructureWhenWorkerProfileIsLoaded() throws IOException {
        var overlay = new YamlPropertySourceLoader().load("worker", new ClassPathResource("application-worker.yml"));
        new ApplicationContextRunner()
                .withUserConfiguration(SandboxGatewayConfiguration.class, AuthRateLimitConfig.class)
                .withInitializer(context -> overlay.forEach(
                        source -> context.getEnvironment().getPropertySources().addLast(source)))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(BucketResolver.class);
                    assertThat(context).doesNotHaveBean(AuthRateLimitConfig.class);
                    BucketResolver resolver = context.getBean(BucketResolver.class);
                    BucketConfiguration limit =
                            new AuthRateLimitProperties.Limit(1, Duration.ofMinutes(1)).bucketConfiguration();
                    assertThat(resolver.resolve("job:1", limit).tryConsume(1)).isTrue();
                    assertThat(resolver.resolve("job:1", limit).tryConsume(1)).isFalse();
                    assertThat(resolver.resolve("job:2", limit).tryConsume(1)).isTrue();
                });
    }
}
