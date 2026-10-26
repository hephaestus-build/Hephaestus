package de.tum.cit.aet.hephaestus.agent.sandbox;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * The worker's sandbox reconciliation, on every runtime: at its start and then periodically, without enabling
 * server-wide {@code @Scheduled} methods.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!specs & !cds-training")
@ConditionalOnWorkerRole
public class SandboxMaintenanceConfiguration {

    @Bean
    ScheduledTaskRegistrar sandboxMaintenanceTasks(
            SandboxReconciler reconciler,
            SandboxProperties sandboxProperties,
            @Value("${hephaestus.sandbox.reconciliation-initial-delay-seconds:10}") long initialDelaySeconds) {
        var tasks = new ScheduledTaskRegistrar();
        tasks.addFixedDelayTask(new FixedDelayTask(
                reconciler::periodicReconciliation,
                Duration.ofSeconds(sandboxProperties.reconciliationIntervalSeconds()),
                Duration.ofSeconds(initialDelaySeconds)));
        return tasks;
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> sandboxStartupMaintenance(SandboxReconciler reconciler) {
        return event -> reconciler.onStartup();
    }
}
