package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.proxy.MentorProxyCredentialRegistry;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive.DockerInteractiveSandboxAdapter;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive.InteractiveSandboxMetrics;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive.InteractiveSandboxRegistry;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive.StdinWriteWatchdog;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxService;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring configuration for the Docker sandbox subsystem.
 *
 * <p>The Docker sandbox <em>is</em> the worker capability (ADR 0005), so it is gated solely on the
 * worker role ({@link RuntimeRole#WORKER_PROPERTY}, {@code matchIfMissing=true} → present in the
 * single-JVM monolith). A non-worker pod (e.g. a webhook-only container) leaves the gate {@code
 * false} and these beans never wire. All sandbox beans are declared as {@code @Bean} methods so
 * they are only created when the role is active — no component-scanning surprises.
 */
@Configuration
@ConditionalOnProperty(name = RuntimeRole.WORKER_PROPERTY, havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(DockerClient.class)
@EnableConfigurationProperties({
    SandboxProperties.class,
    DockerSandboxProperties.class,
    InteractiveSandboxProperties.class
})
public class DockerSandboxConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxConfiguration.class);

    /** RPC connections per container: create/start, logs, and a copy-out lease held while it is read. */
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

    /** Calls whose response body is the stream. One wait per container, and nothing else. */
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
                "Docker sandbox client configured: kind={}, host={}, tlsVerify={}, responseTimeout={}, maxConnections={}",
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
    public SandboxNetworkManager sandboxNetworkManager(DockerClientOperations ops, DockerSandboxProperties properties) {
        return new SandboxNetworkManager(ops, properties);
    }

    @Bean
    public SandboxWorkspaceManager sandboxWorkspaceManager(DockerClientOperations ops, SandboxProperties properties) {
        return new SandboxWorkspaceManager(
                ops,
                SandboxWorkspaceManager.MAX_OUTPUT_BYTES,
                SandboxWorkspaceManager.MAX_SINGLE_FILE_BYTES,
                properties.maxDirectoryBytes(),
                properties.maxDirectoryEntries());
    }

    /**
     * Dedicated platform thread pool for Docker blocking wait operations.
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
            ExecutorService dockerWaitExecutor) {
        return new SandboxContainerManager(ops, imageGuard, properties, dockerWaitExecutor);
    }

    @Bean
    public SandboxManager dockerSandboxAdapter(
            SandboxNetworkManager networkManager,
            SandboxWorkspaceManager workspaceManager,
            SandboxContainerManager containerManager,
            ContainerSecurityPolicy securityPolicy,
            SandboxGatewayProperties gatewayProperties,
            MeterRegistry meterRegistry) {
        return new DockerSandboxAdapter(
                networkManager,
                workspaceManager,
                containerManager,
                securityPolicy,
                gatewayProperties.port(),
                meterRegistry);
    }

    @Bean
    public SandboxReconciler sandboxReconciler(
            AgentJobRepository jobRepository,
            SandboxContainerManager containerManager,
            SandboxNetworkManager networkManager,
            MeterRegistry meterRegistry,
            Clock clock) {
        return new SandboxReconciler(jobRepository, containerManager, networkManager, meterRegistry, clock);
    }

    @Bean
    public DockerHealthIndicator dockerHealthIndicator(
            SandboxContainerManager containerManager,
            SandboxProperties properties,
            DockerSandboxProperties dockerProperties) {
        return new DockerHealthIndicator(containerManager, properties, dockerProperties);
    }

    // Interactive (mentor) sandbox — sibling of SandboxManager.

    @Bean
    public InteractiveSandboxMetrics interactiveSandboxMetrics(MeterRegistry meterRegistry) {
        return new InteractiveSandboxMetrics(meterRegistry);
    }

    @Bean
    public StdinWriteWatchdog stdinWriteWatchdog() {
        return new StdinWriteWatchdog();
    }

    @Bean
    public InteractiveSandboxRegistry interactiveSandboxRegistry(
            InteractiveSandboxProperties properties,
            SandboxContainerManager containerManager,
            InteractiveSandboxMetrics metrics,
            StdinWriteWatchdog watchdog,
            MeterRegistry meterRegistry) {
        return new InteractiveSandboxRegistry(properties, containerManager, metrics, watchdog, meterRegistry);
    }

    @Bean
    public InteractiveSandboxService dockerInteractiveSandboxAdapter(
            InteractiveSandboxProperties interactiveProperties,
            SandboxNetworkManager networkManager,
            SandboxWorkspaceManager workspaceManager,
            SandboxContainerManager containerManager,
            ContainerSecurityPolicy securityPolicy,
            InteractiveSandboxRegistry registry,
            InteractiveSandboxMetrics metrics,
            ObjectMapper mapper,
            // Shared platform-thread executor (declared above for the sync sandbox); the interactive
            // adapter runs runClose() here too — docker-java sync calls pin virtual carriers on JDK 21.
            ExecutorService dockerWaitExecutor,
            DockerSandboxProperties dockerProperties,
            SandboxGatewayProperties gatewayProperties,
            MentorProxyCredentialRegistry mentorProxyCredentialRegistry) {
        return new DockerInteractiveSandboxAdapter(
                interactiveProperties,
                networkManager,
                workspaceManager,
                containerManager,
                securityPolicy,
                registry,
                metrics,
                mapper,
                dockerWaitExecutor,
                dockerProperties,
                gatewayProperties.port(),
                mentorProxyCredentialRegistry);
    }

    /**
     * Bounded platform thread pool for sandbox Docker operations.
     *
     * <p>Uses direct handoff ({@code queueCapacity=0}) so that when all threads are occupied,
     * submissions are rejected immediately. That rejection is the backpressure: the claim is undone
     * and the row goes back to QUEUED for a later poll.
     */
    @Bean(name = "sandboxExecutor")
    public AsyncTaskExecutor sandboxExecutor(SandboxProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        int maxPool = properties.maxConcurrentContainers();
        executor.setCorePoolSize(Math.min(2, maxPool));
        executor.setMaxPoolSize(maxPool);
        executor.setQueueCapacity(0);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("sandbox-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        log.info("Sandbox executor configured: maxConcurrent={}", properties.maxConcurrentContainers());
        return executor;
    }

    // Internal helpers

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
