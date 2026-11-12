package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * The isolation probe of a server on Kubernetes ({@link KubernetesIsolationProbe}): once the gateway listens at the
 * start, on a thread of its own so that the API never waits for it, and then every
 * {@link KubernetesSandboxProperties#probeInterval}. Until a probe passes, and after one fails, the server starts no
 * sandbox. The registrar has a thread of its own, so a probe delays no other maintenance.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!specs & !cds-training")
@ConditionalOnAgentRuntime
public class KubernetesSandboxMaintenanceConfiguration {

    @Bean
    ApplicationListener<ApplicationReadyEvent> kubernetesIsolationProof(KubernetesIsolationProbe probe) {
        return event -> Thread.ofVirtual().name("sandbox-isolation-proof").start(probe::proveAtStart);
    }

    @Bean
    @Profile("!test")
    ScheduledTaskRegistrar kubernetesIsolationReprobe(
            KubernetesIsolationProbe probe, KubernetesSandboxProperties properties) {
        var tasks = new ScheduledTaskRegistrar();
        tasks.addFixedDelayTask(new FixedDelayTask(probe::run, properties.probeInterval(), properties.probeInterval()));
        return tasks;
    }
}
