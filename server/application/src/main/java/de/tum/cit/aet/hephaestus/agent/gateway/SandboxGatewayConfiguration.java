package de.tum.cit.aet.hephaestus.agent.gateway;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The connector sandboxes reach the worker on. Present wherever the worker role is (ADR 0005), so
 * the monolith opens it too.
 */
@Configuration
@ConditionalOnProperty(name = RuntimeRole.WORKER_PROPERTY, havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SandboxGatewayProperties.class)
public class SandboxGatewayConfiguration {

    /** Far above any worker's concurrent-sandbox working set; bounds a pathological key stream. */
    private static final int MAX_BUCKETS = 10_000;

    /**
     * Deliberately carries no {@code address}, so it binds every interface: a sandbox dials the worker
     * across a per-job Docker bridge, on an address the worker does not know in advance. The
     * application and management connectors are the ones {@code server.address} keeps on loopback.
     */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> sandboxGatewayConnector(
            SandboxGatewayProperties properties) {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(properties.port());
            factory.addAdditionalConnectors(connector);
        };
    }

    /**
     * Buckets for the gateway's rate limit on a pod that runs no server role, where the worker overlay
     * drops {@code core.auth} and with it the instance's shared resolver. Limits are per worker:
     * sandbox adapters route to their worker's address on the job network, not a load balancer.
     * This is local flood protection, not a fleet-wide quota. A pod running the server role shares
     * its auth resolver instead.
     */
    @Bean
    @ConditionalOnProperty(name = RuntimeRole.SERVER_PROPERTY, havingValue = "false")
    BucketResolver sandboxGatewayBucketResolver() {
        Cache<String, Bucket> buckets = Caffeine.newBuilder()
                .maximumSize(MAX_BUCKETS)
                .expireAfterAccess(Duration.ofHours(1))
                .build();
        return (key, configuration) -> buckets.get(
                key,
                ignored -> Bucket.builder()
                        .addLimit(configuration.getBandwidths()[0])
                        .build());
    }
}
