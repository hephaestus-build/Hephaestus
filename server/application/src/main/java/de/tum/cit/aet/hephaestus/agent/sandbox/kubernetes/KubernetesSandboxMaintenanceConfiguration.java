package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * The isolation probe of a worker on Kubernetes ({@link KubernetesIsolationProbe}): once the gateway listens at the
 * start, where a probe that fails stops the worker, and then every {@link KubernetesSandboxProperties#probeInterval},
 * where a probe that fails stops new sandboxes until one passes again. The registrar has a thread of its own, so a probe
 * delays no other maintenance.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!specs & !cds-training")
@ConditionalOnWorkerRole
public class KubernetesSandboxMaintenanceConfiguration {

    @Bean
    ApplicationListener<ApplicationReadyEvent> kubernetesIsolationProof(KubernetesIsolationProbe probe) {
        return event -> probe.prove();
    }

    @Bean
    ScheduledTaskRegistrar kubernetesIsolationReprobe(
            KubernetesIsolationProbe probe, KubernetesSandboxProperties properties) {
        var tasks = new ScheduledTaskRegistrar();
        tasks.addFixedDelayTask(new FixedDelayTask(probe::run, properties.probeInterval(), properties.probeInterval()));
        return tasks;
    }
}
