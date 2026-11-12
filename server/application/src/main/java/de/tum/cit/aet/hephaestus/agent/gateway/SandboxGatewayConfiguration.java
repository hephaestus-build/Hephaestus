package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The connector sandboxes reach the server on, its gateway port (ADR 0005). The public API never serves it. */
@Configuration
@ConditionalOnAgentRuntime
@EnableConfigurationProperties(SandboxGatewayProperties.class)
public class SandboxGatewayConfiguration {

    /**
     * Deliberately carries no {@code address}, so it binds every interface: a sandbox dials the server
     * from its Pod, or through the k3d host address during development.
     */
    @Bean
    SandboxGatewayConnector sandboxGatewayConnector(SandboxGatewayProperties properties) {
        return new SandboxGatewayConnector(properties.port(), properties.maxConnections());
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> sandboxGatewayConnectorCustomizer(
            SandboxGatewayConnector gateway) {
        return factory -> {
            factory.addAdditionalConnectors(gateway.connector());
            factory.addEngineValves(new SandboxGatewayPeerValve());
        };
    }

    @Bean
    MeterBinder sandboxGatewayConnectionMeters(SandboxGatewayConnector gateway) {
        return registry -> {
            Gauge.builder(AgentMetrics.SANDBOX_GATEWAY_CONNECTIONS, gateway, SandboxGatewayConnector::connections)
                    .description("Connections open on the sandbox gateway")
                    .register(registry);
            Gauge.builder(
                            AgentMetrics.SANDBOX_GATEWAY_CONNECTIONS_LIMIT,
                            gateway,
                            SandboxGatewayConnector::maxConnections)
                    .description("Connections that the sandbox gateway holds open at most")
                    .register(registry);
        };
    }
}
