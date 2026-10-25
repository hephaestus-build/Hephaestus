package de.tum.cit.aet.hephaestus.agent.sandbox;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.HostSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * What every sandbox runtime of a worker shares: the selection of the runtime, the pool that runs practice reviews, and
 * the attempt protocol, the Heph hosts and the reconciler over the runtime's {@link SandboxBackend}. The runtime's own
 * configuration ({@code DockerSandboxConfiguration} or {@code KubernetesSandboxConfiguration}) loads only when
 * {@link SandboxRuntime#PROPERTY} names it, and gives the backend.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWorkerRole
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxRuntimeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SandboxRuntimeConfiguration.class);

    /**
     * The selected runtime. The runtimes' property conditions match by name, so a value that names no runtime loads
     * none of them; binding it here makes that value stop the worker's start.
     */
    @Bean
    public SandboxRuntime sandboxRuntime(Environment environment) {
        SandboxRuntime runtime = Binder.get(environment)
                .bind(SandboxRuntime.PROPERTY, SandboxRuntime.class)
                .orElse(SandboxRuntime.DOCKER);
        log.info("Sandbox runtime: {}", runtime);
        return runtime;
    }

    @Bean
    public SandboxWorkspaceManager sandboxWorkspaceManager() {
        return new SandboxWorkspaceManager();
    }

    @Bean
    public SandboxManager sandboxAttemptRunner(
            SandboxBackend backend,
            SandboxWorkspaceManager workspaceManager,
            SandboxGatewaySessions gatewaySessions,
            MeterRegistry meterRegistry) {
        return new SandboxAttemptRunner(backend, workspaceManager, gatewaySessions, meterRegistry);
    }

    @Bean
    public HostSandbox sandboxHosts(
            SandboxBackend backend,
            SandboxWorkspaceManager workspaceManager,
            SandboxGatewaySessions gatewaySessions,
            AgentImageProperties agentImageProperties) {
        return new SandboxHosts(backend, workspaceManager, gatewaySessions, agentImageProperties.reference());
    }

    @Bean
    public SandboxReconciler sandboxReconciler(
            ReviewJobRepository jobRepository, SandboxBackend backend, MeterRegistry meterRegistry, Clock clock) {
        return new SandboxReconciler(jobRepository, backend, meterRegistry, clock);
    }

    /**
     * Bounded platform thread pool for sandbox executions.
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
}
