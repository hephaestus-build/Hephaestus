package de.tum.cit.aet.hephaestus.agent.sandbox;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentPhaseObservations;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** The worker's review executor, attempt protocol, host workspaces, and reconciliation over its Kubernetes backend. */
@Configuration(proxyBeanMethods = false)
@Profile("!specs & !cds-training")
@ConditionalOnWorkerRole
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxRuntimeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SandboxRuntimeConfiguration.class);

    @Bean
    public SandboxWorkspaceManager sandboxWorkspaceManager() {
        return new SandboxWorkspaceManager();
    }

    @Bean
    public SandboxManager sandboxAttemptRunner(
            SandboxBackend backend,
            SandboxWorkspaceManager workspaceManager,
            SandboxGatewaySessions gatewaySessions,
            MeterRegistry meterRegistry,
            AgentPhaseObservations phases) {
        return new SandboxAttemptRunner(backend, workspaceManager, gatewaySessions, meterRegistry, phases);
    }

    @Bean
    public HostSandbox sandboxHosts(
            SandboxBackend backend,
            SandboxWorkspaceManager workspaceManager,
            SandboxGatewaySessions gatewaySessions,
            AgentImageProperties agentImageProperties,
            AgentPhaseObservations phases) {
        return new SandboxHosts(backend, workspaceManager, gatewaySessions, agentImageProperties.reference(), phases);
    }

    @Bean
    public SandboxReconciler sandboxReconciler(
            ReviewJobRepository jobRepository, SandboxBackend backend, MeterRegistry meterRegistry, Clock clock) {
        return new SandboxReconciler(jobRepository, backend, meterRegistry, clock);
    }

    /**
     * Platform threads for review attempts, one per claimed attempt.
     *
     * <p>The executor's claim bound limits simultaneous sandboxes to {@code maxConcurrentContainers}. An attempt whose
     * sandbox ended keeps its thread while the worker composes its feedback, but not its sandbox slot, so the pool is
     * not that bound. It hands off directly ({@code queueCapacity=0}): a rejected submission undoes the claim and the
     * row goes back to QUEUED for a later poll.
     */
    @Bean(name = "sandboxExecutor")
    public AsyncTaskExecutor sandboxExecutor(SandboxProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.min(2, properties.maxConcurrentContainers()));
        executor.setMaxPoolSize(Integer.MAX_VALUE);
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
