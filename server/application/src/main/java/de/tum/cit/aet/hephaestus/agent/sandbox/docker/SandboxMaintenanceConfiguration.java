package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import com.github.dockerjava.api.DockerClient;
import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive.InteractiveSandboxRegistry;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive.StdinWriteWatchdog;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** Worker-owned maintenance without enabling server-wide {@code @Scheduled} methods. */
@Configuration(proxyBeanMethods = false)
@Profile("!specs & !cds-training")
@ConditionalOnClass(DockerClient.class)
@ConditionalOnProperty(name = RuntimeRole.WORKER_PROPERTY, havingValue = "true", matchIfMissing = true)
public class SandboxMaintenanceConfiguration {

    @Bean
    ScheduledTaskRegistrar sandboxWatchdogTasks(StdinWriteWatchdog watchdog) {
        var tasks = new ScheduledTaskRegistrar();
        // Docker reconciliation can block on RPCs; stalled-write protection needs its own thread.
        tasks.addFixedDelayTask(new FixedDelayTask(watchdog::tick, Duration.ofMillis(100), Duration.ZERO));
        return tasks;
    }

    @Bean
    ScheduledTaskRegistrar sandboxMaintenanceTasks(
            SandboxReconciler reconciler,
            InteractiveSandboxRegistry sessions,
            SandboxProperties sandboxProperties,
            InteractiveSandboxProperties mentorProperties,
            @Value("${hephaestus.sandbox.reconciliation-initial-delay-seconds:10}") long initialDelaySeconds) {
        var tasks = new ScheduledTaskRegistrar();
        tasks.addFixedDelayTask(new FixedDelayTask(
                reconciler::periodicReconciliation,
                Duration.ofSeconds(sandboxProperties.reconciliationIntervalSeconds()),
                Duration.ofSeconds(initialDelaySeconds)));
        tasks.addFixedDelayTask(new FixedDelayTask(
                sessions::reap, Duration.ofSeconds(mentorProperties.reapIntervalSeconds()), Duration.ZERO));
        return tasks;
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> sandboxStartupMaintenance(
            SandboxReconciler reconciler, InteractiveSandboxRegistry sessions) {
        return event -> {
            reconciler.onStartup();
            sessions.onStartup();
        };
    }
}
