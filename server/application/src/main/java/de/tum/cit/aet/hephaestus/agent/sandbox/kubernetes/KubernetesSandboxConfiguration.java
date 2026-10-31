package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.job.WorkerRegistryRepository;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.okhttp.OkHttpClientFactory;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The worker's Kubernetes runtime: review Jobs, a conversation host, and turn Pods.
 * The client uses the worker Pod's service account, or the host dev launcher's namespace-scoped kubeconfig.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!specs & !cds-training")
@ConditionalOnWorkerRole
@EnableConfigurationProperties({SandboxProperties.class, KubernetesSandboxProperties.class})
public class KubernetesSandboxConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KubernetesSandboxConfiguration.class);

    /** Bounds every request but a watch or an exec, which bound themselves. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);

    @Bean(destroyMethod = "close")
    public KubernetesClient sandboxKubernetesClient() {
        Config config = new ConfigBuilder(Config.autoConfigure(null))
                .withRequestTimeout((int) REQUEST_TIMEOUT.toMillis())
                .withConnectionTimeout((int) CONNECTION_TIMEOUT.toMillis())
                .build();
        log.info("Kubernetes sandbox client configured: requestTimeout={}", REQUEST_TIMEOUT);
        return clientBuilder().withConfig(config).build();
    }

    static KubernetesClientBuilder clientBuilder() {
        return new KubernetesClientBuilder().withHttpClientFactory(new OkHttpClientFactory() {
            @Override
            protected void additionalConfig(OkHttpClient.Builder builder) {
                // An exec uses a bodyless GET; connection recovery must not send it a second time.
                builder.retryOnConnectionFailure(false);
            }
        });
    }

    @Bean
    public KubernetesWorker kubernetesWorker(
            KubernetesSandboxProperties properties,
            WorkerProperties workerProperties,
            SandboxGatewayProperties gatewayProperties) {
        // The chart's NetworkPolicy names the port, so a port that the web server chooses at start cannot work here.
        if (gatewayProperties.port() == 0) {
            throw new IllegalStateException(
                    "The Kubernetes sandbox runtime needs a fixed sandbox gateway port (SANDBOX_API_PORT)");
        }
        return new KubernetesWorker(properties, workerProperties.resolvedWorkerId(), gatewayProperties.port());
    }

    @Bean
    public KubernetesLiveWorkers kubernetesLiveWorkers(WorkerRegistryRepository registry) {
        return new KubernetesLiveWorkers(registry);
    }

    @Bean
    public KubernetesPods kubernetesPods(
            KubernetesClient sandboxKubernetesClient,
            KubernetesWorker worker,
            SandboxProperties properties,
            AgentImageProperties agentImageProperties) {
        return new KubernetesPods(
                sandboxKubernetesClient,
                worker,
                properties.containerStopTimeoutSeconds(),
                agentImageProperties.pullPolicy());
    }

    @Bean(destroyMethod = "close")
    public KubernetesSandboxCache kubernetesSandboxCache(
            KubernetesClient sandboxKubernetesClient, KubernetesWorker worker) {
        return new KubernetesSandboxCache(sandboxKubernetesClient, worker.namespace());
    }

    @Bean
    public KubernetesSandboxJobs kubernetesSandboxJobs(
            KubernetesClient sandboxKubernetesClient, KubernetesWorker worker) {
        return new KubernetesSandboxJobs(sandboxKubernetesClient, worker);
    }

    @Bean(destroyMethod = "close")
    public KubernetesToolsJobs kubernetesToolsJobs(
            KubernetesClient sandboxKubernetesClient,
            KubernetesPods pods,
            KubernetesSandboxJobs jobs,
            KubernetesWorker worker,
            AgentImageProperties agentImageProperties) {
        return new KubernetesToolsJobs(sandboxKubernetesClient, pods, jobs, worker, agentImageProperties.reference());
    }

    @Bean
    public SandboxExits sandboxExits(KubernetesPods pods, MeterRegistry meters) {
        return new SandboxExits(pods, meters);
    }

    @Bean
    public SandboxBackend kubernetesSandboxBackend(
            KubernetesPods pods,
            KubernetesSandboxJobs reviewJobs,
            KubernetesWorker worker,
            KubernetesLiveWorkers liveWorkers,
            SandboxExits exits,
            KubernetesSandboxCache cache) {
        return new KubernetesSandboxBackend(pods, reviewJobs, worker, liveWorkers, exits, cache);
    }

    @Bean(destroyMethod = "close")
    public KubernetesExecSandboxes kubernetesExecSandboxes(
            KubernetesPods pods,
            KubernetesWorker worker,
            AgentImageProperties agentImageProperties,
            KubernetesLiveWorkers liveWorkers,
            SandboxExits exits,
            KubernetesSandboxCache cache) {
        return new KubernetesExecSandboxes(pods, worker, agentImageProperties.reference(), liveWorkers, exits, cache);
    }

    @Bean
    public KubernetesIsolationProbe kubernetesIsolationProbe(
            KubernetesClient sandboxKubernetesClient,
            KubernetesPods pods,
            KubernetesSandboxJobs jobs,
            KubernetesWorker worker,
            AgentImageProperties agentImageProperties,
            @Value("${management.server.port:9090}") int managementPort) {
        return new KubernetesIsolationProbe(
                sandboxKubernetesClient, pods, jobs, worker, agentImageProperties.reference(), managementPort);
    }

    /** The isolation tier as gauges: whether the latest probe passed, the user namespaces, the process limit. */
    @Bean
    public MeterBinder kubernetesIsolationMetrics(KubernetesWorker worker) {
        return registry -> {
            Gauge.builder(AgentMetrics.SANDBOX_ISOLATION_PASSED, worker, w -> w.isolated() ? 1 : 0)
                    .description(
                            "Whether the latest sandbox isolation probe passed; no sandbox starts while it did not")
                    .register(registry);
            Gauge.builder(AgentMetrics.SANDBOX_ISOLATION_USER_NAMESPACES, worker, w -> w.userNamespaces() ? 1 : 0)
                    .description("Whether sandbox Pods run in user namespaces of their own")
                    .register(registry);
            Gauge.builder(AgentMetrics.SANDBOX_ISOLATION_PROCESS_LIMIT, worker, w -> {
                        KubernetesIsolationProbe.Proof proof = w.proof();
                        Integer limit = proof == null ? null : proof.processLimit();
                        return limit == null ? Double.NaN : limit;
                    })
                    .description("The processes a sandbox Pod could start before the node refused one; NaN for none")
                    .register(registry);
        };
    }

    /**
     * Readiness: down while the worker does not watch the sandbox namespace or the
     * isolation probe failed. The details give the isolation tier of the latest probe.
     */
    @Bean
    public HealthIndicator kubernetesSandboxHealthIndicator(KubernetesSandboxCache cache, KubernetesWorker worker) {
        return () -> {
            KubernetesIsolationProbe.Proof proof = worker.proof();
            // The informers' watches stand for the API: a readiness probe then sends it nothing.
            if (!cache.current()) {
                return Health.down()
                        .withDetail("error", "Kubernetes API not watched: the sandbox namespace cache is not current")
                        .build();
            }
            if (proof == null) {
                return Health.down()
                        .withDetail("isolation", "The isolation probe has not run yet")
                        .build();
            }
            Health.Builder health = proof.passed() ? Health.up() : Health.down();
            health.withDetail("isolationProvedAt", proof.at().toString()).withDetail("isolationTier", proof.tier());
            if (!proof.passed()) {
                health.withDetail("isolationFailures", proof.failures());
            }
            return health.build();
        };
    }
}
