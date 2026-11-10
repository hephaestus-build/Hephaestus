package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunNotifications;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** The dispatch of sandboxes, where the sandbox owner is. */
@Configuration(proxyBeanMethods = false)
@Profile("!specs & !cds-training")
@ConditionalOnWorkerRole
class SandboxDispatchConfiguration {

    /**
     * Review sandboxes are limited by the review count that the chart gives each worker today. RUN-6 moves the count
     * into the capacity home, as one count for every replica.
     */
    @Bean
    SandboxDispatcher sandboxDispatcher(
            AgentSandboxes sandboxes,
            AgentRuns runs,
            List<SandboxRunKind> kinds,
            SandboxProperties sandbox,
            ReviewAttemptCustody custody,
            MeterRegistry meters) {
        return new SandboxDispatcher(
                sandboxes,
                runs,
                kinds,
                Map.of(AgentSandboxKind.REVIEW, sandbox.maxConcurrentContainers()),
                custody,
                meters);
    }

    /**
     * A pass when a run is queued, and one every lease renewal for runs that became due without a notification: its own
     * scheduler thread, since the worker role does not enable {@code @Scheduled}.
     */
    @Bean
    @Profile("!test")
    ScheduledTaskRegistrar sandboxDispatchTask(
            SandboxDispatcher dispatcher, AgentRunNotifications notifications, AgentCapacityProperties capacity) {
        notifications.subscribe(kind -> dispatcher.wake());
        var tasks = new ScheduledTaskRegistrar();
        Duration period = capacity.leaseRenewal();
        tasks.addFixedDelayTask(new FixedDelayTask(dispatcher::wake, period, Duration.ZERO));
        return tasks;
    }
}
