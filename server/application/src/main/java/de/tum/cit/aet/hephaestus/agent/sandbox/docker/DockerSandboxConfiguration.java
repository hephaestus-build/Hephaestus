package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConnector;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxRuntime;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxRuntimeConfiguration;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;

/**
 * Spring configuration for the Docker sandbox subsystem.
 *
 * <p>The sandbox is the worker capability (ADR 0005), so it is gated on the worker role
 * ({@link RuntimeRole#WORKER_PROPERTY}, {@code matchIfMissing=true} → present in the single-JVM monolith), and on
 * Docker as the selected {@link SandboxRuntime}, its default. A non-worker pod (e.g. a webhook-only container) leaves
 * the gate {@code false} and these beans never wire. All sandbox beans are declared as {@code @Bean} methods so they
 * are only created when the role is active — no component-scanning surprises. What every runtime shares is in
 * {@link SandboxRuntimeConfiguration}.
 */
@Configuration
@ConditionalOnWorkerRole
@ConditionalOnProperty(name = SandboxRuntime.PROPERTY, havingValue = "docker", matchIfMissing = true)
@ConditionalOnClass(DockerClient.class)
@EnableConfigurationProperties({SandboxProperties.class, DockerSandboxProperties.class})
public class DockerSandboxConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxConfiguration.class);

    private final Environment environment;

    public DockerSandboxConfiguration(Environment environment) {
        this.environment = environment;
    }

    /** RPC connections per container: create/start, logs and the wait. */
    private static final int RPC_CONNECTIONS_PER_CONTAINER = 3;

    private static final Duration HTTP_CONNECTION_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Idle timeout, not a deadline: Apache installs responseTimeout as the socket timeout for the
     * whole exchange, so it bounds the gap between reads and a call that keeps producing bytes runs
     * as long as it likes. Every RPC call carries its own budget, so this only has to reclaim the
     * connection when the daemon goes silent — generous enough that a slow image layer or a large
     * archive upload never trips it.
     */
    static final Duration HTTP_RESPONSE_TIMEOUT = Duration.ofMinutes(30);

    /**
     * `docker wait` sends nothing until the container exits, so for it the idle timeout is a ceiling
     * on the container's life. Sitting above {@link ResourceLimits#MAX_RUNTIME} it can never cut a
     * legitimate wait short, while still reclaiming a connection the daemon has abandoned —
     * docker-java's reader thread only ever gets an interrupt, which a blocking read ignores.
     */
    static final Duration HTTP_STREAMING_RESPONSE_TIMEOUT = ResourceLimits.MAX_RUNTIME.plusMinutes(10);

    /** Idle bound of a turn sandbox call: a command is silent for at most its one-minute timeout and the kill after. */
    private static final Duration TURN_SANDBOX_RESPONSE_TIMEOUT = Duration.ofMinutes(5);

    /** Turn sandbox calls at once: a few per running Heph turn, and the pool refill. */
    private static final int TURN_SANDBOX_CONNECTIONS = 32;

    /** Calls whose response body is the stream: the wait on every container. */
    @Bean(name = "dockerStreamingClient", destroyMethod = "close")
    public DockerClient dockerStreamingClient(SandboxProperties properties, DockerSandboxProperties dockerProperties) {
        return buildClient(
                dockerProperties, HTTP_STREAMING_RESPONSE_TIMEOUT, properties.maxConcurrentContainers(), "streaming");
    }

    @Bean(destroyMethod = "close")
    public DockerClient dockerClient(SandboxProperties properties, DockerSandboxProperties dockerProperties) {
        return buildClient(
                dockerProperties,
                HTTP_RESPONSE_TIMEOUT,
                properties.maxConcurrentContainers() * RPC_CONNECTIONS_PER_CONTAINER,
                "rpc");
    }

    private DockerClient buildClient(
            DockerSandboxProperties properties, Duration responseTimeout, int maxConnections, String kind) {
        // A bare Builder, not createDefaultConfigBuilder(): the latter seeds itself from
        // DOCKER_HOST/DOCKER_TLS_VERIFY/DOCKER_CERT_PATH/DOCKER_CONTEXT before these three
        // overrides land, so an ambient value would win for any field we don't set here.
        var configBuilder = new DefaultDockerClientConfig.Builder()
                .withDockerHost(properties.host())
                .withDockerTlsVerify(properties.tlsVerify());
        if (properties.certPath() != null) {
            configBuilder.withDockerCertPath(properties.certPath());
        }

        var config = configBuilder.build();

        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(maxConnections)
                .connectionTimeout(HTTP_CONNECTION_TIMEOUT)
                .responseTimeout(responseTimeout)
                .build();

        DockerClient client = DockerClientImpl.getInstance(config, new ResponseOwnedDockerHttpClient(httpClient));
        log.info(
                "Docker sandbox client configured: kind={}, host={}, tlsVerify={}, responseTimeout={},"
                        + " maxConnections={}",
                kind,
                properties.host(),
                properties.tlsVerify(),
                responseTimeout,
                maxConnections);

        return client;
    }

    @Bean
    public DockerClientOperations dockerClientOperations(
            DockerClient dockerClient, @Qualifier("dockerStreamingClient") DockerClient dockerStreamingClient) {
        return new DockerClientOperations(dockerClient, dockerStreamingClient);
    }

    @Bean
    public ContainerSecurityPolicy containerSecurityPolicy(DockerSandboxProperties properties) {
        String seccompJson = loadSeccompProfile("sandbox/agent-seccomp-profile.json");
        return new ContainerSecurityPolicy(properties, seccompJson);
    }

    @Bean
    public SandboxCreator sandboxCreator(DockerClientOperations ops) {
        return new SandboxCreator(ops);
    }

    @Bean
    public SandboxNetworkManager sandboxNetworkManager(
            DockerClientOperations ops, DockerSandboxProperties properties, SandboxCreator creator) {
        return new SandboxNetworkManager(ops, properties, creator);
    }

    /**
     * Dedicated platform thread pool for Docker blocking wait operations, one thread per sandbox that
     * may run at once.
     *
     * <p>docker-java's Apache HttpClient5 has {@code synchronized} blocks that pin virtual threads in
     * Java 21, causing cascading failures. A dedicated bounded pool of platform threads avoids this.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService dockerWaitExecutor(SandboxProperties properties) {
        return Executors.newFixedThreadPool(
                properties.maxConcurrentContainers(),
                Thread.ofPlatform().name("docker-wait-", 0).daemon(true).factory());
    }

    @Bean
    public SandboxImageGuard sandboxImageGuard(
            DockerClientOperations ops, AgentImageProperties agentImageProperties, MeterRegistry meterRegistry) {
        return image -> ImagePullBootstrapperSupport.applyPolicy(
                image,
                agentImageProperties.pullPolicy(),
                ops,
                AgentMetrics.SANDBOX_IMAGE_PULL_DURATION,
                AgentMetrics.SANDBOX_IMAGE_PULL_FAILURE,
                AgentMetrics.SANDBOX_IMAGE_PULL_SKIPPED,
                meterRegistry,
                log);
    }

    @Bean
    public SandboxContainerManager sandboxContainerManager(
            DockerClientOperations ops,
            SandboxImageGuard imageGuard,
            SandboxProperties properties,
            DockerSandboxProperties dockerProperties,
            ExecutorService dockerWaitExecutor) {
        return new SandboxContainerManager(ops, imageGuard, properties, dockerProperties.owner(), dockerWaitExecutor);
    }

    @Bean
    public SandboxBackend dockerSandboxBackend(
            SandboxNetworkManager networkManager,
            SandboxContainerManager containerManager,
            ContainerSecurityPolicy securityPolicy,
            DockerClientOperations volumeOperations,
            SandboxCreator creator,
            DockerSandboxProperties dockerProperties,
            SandboxGatewayConnector gateway,
            SandboxProperties sandboxProperties) {
        ContainerSecurityPolicy.requireRoomBesideTmpfs(
                sandboxProperties.defaultResourceLimits().memoryBytes());
        return new DockerSandboxBackend(
                networkManager,
                containerManager,
                securityPolicy,
                volumeOperations,
                creator,
                dockerProperties.owner(),
                gateway::port);
    }

    /**
     * Heph's turn sandboxes, with a Docker client of their own: a command can wait for output for a minute, and its
     * connections must not take the ones that the practice reviews wait on.
     */
    @Bean(destroyMethod = "close")
    public DockerExecSandboxes dockerExecSandboxes(
            DockerSandboxProperties dockerProperties,
            DockerClientOperations operations,
            ContainerSecurityPolicy securityPolicy,
            SandboxImageGuard imageGuard,
            SandboxCreator creator,
            AgentImageProperties agentImageProperties) {
        return new DockerExecSandboxes(
                buildClient(dockerProperties, TURN_SANDBOX_RESPONSE_TIMEOUT, TURN_SANDBOX_CONNECTIONS, "turn-sandbox"),
                operations,
                securityPolicy,
                imageGuard,
                creator,
                agentImageProperties.reference(),
                dockerProperties.owner(),
                environment.getProperty("server.port", Integer.class, 8080));
    }

    @Bean
    public DockerHealthIndicator dockerHealthIndicator(
            SandboxContainerManager containerManager,
            SandboxProperties properties,
            DockerSandboxProperties dockerProperties) {
        return new DockerHealthIndicator(containerManager, properties, dockerProperties);
    }

    /**
     * Load a seccomp profile from the classpath once at startup.
     *
     * <p>Fails hard if the resource is missing — a missing seccomp profile would silently degrade
     * container security, which is unacceptable.
     *
     * @param resourcePath classpath resource path
     * @return the JSON string
     * @throws SandboxException if the resource is missing or unreadable
     */
    private String loadSeccompProfile(String resourcePath) {
        try {
            var resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                throw new SandboxException("Required seccomp profile not found on classpath: " + resourcePath
                        + ". Sandbox cannot start without a seccomp profile.");
            }
            try (var is = resource.getInputStream()) {
                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                log.info("Loaded seccomp profile: {} ({} bytes)", resourcePath, json.length());
                return json;
            }
        } catch (SandboxException e) {
            throw e;
        } catch (IOException e) {
            throw new SandboxException("Failed to load seccomp profile: " + resourcePath, e);
        }
    }
}
