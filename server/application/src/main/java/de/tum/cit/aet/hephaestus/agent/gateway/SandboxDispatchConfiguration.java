package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunNotifications;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnBackgroundWork;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** The dispatch of sandboxes, where the sandbox owner is. */
@Configuration(proxyBeanMethods = false)
@Profile("!specs & !cds-training")
@ConditionalOnAgentRuntime
class SandboxDispatchConfiguration {

    /**
     * Review sandboxes are limited by the review count that the chart gives each server today. RUN-6 moves the count
     * into the capacity home, as one count for every replica.
     */
    @Bean
    SandboxDispatcher sandboxDispatcher(
            AgentSandboxes sandboxes,
            AgentRuns runs,
            List<SandboxRunKind> kinds,
            SandboxProperties sandbox,
            AgentCapacityProperties capacity,
            ReviewAttemptCustody custody,
            MeterRegistry meters) {
        return new SandboxDispatcher(
                sandboxes,
                runs,
                kinds,
                Map.of(
                        AgentSandboxKind.REVIEW,
                        sandbox.maxConcurrentContainers(),
                        AgentSandboxKind.CONVERSATION,
                        capacity.turns() == null ? Integer.MAX_VALUE : capacity.turns()),
                custody,
                meters);
    }

    /**
     * A pass when a run is queued, and one every lease renewal for runs that became due without a notification, on its
     * own scheduler thread.
     */
    @Bean
    @ConditionalOnBackgroundWork
    DispatchPasses sandboxDispatchPasses(
            SandboxDispatcher dispatcher, AgentRunNotifications notifications, AgentCapacityProperties capacity) {
        return new DispatchPasses(dispatcher, notifications, capacity.leaseRenewal());
    }

    /**
     * Starts the passes when the application is ready, not while its context starts: no sandbox starts before the
     * server serves its gateway, and the run listener's connection opens only then.
     */
    static final class DispatchPasses implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

        private final ScheduledTaskRegistrar tasks = new ScheduledTaskRegistrar();
        private final SandboxDispatcher dispatcher;
        private final AgentRunNotifications notifications;
        private final Duration period;
        private final AtomicBoolean started = new AtomicBoolean();

        DispatchPasses(SandboxDispatcher dispatcher, AgentRunNotifications notifications, Duration period) {
            this.dispatcher = dispatcher;
            this.notifications = notifications;
            this.period = period;
        }

        @Override
        public void onApplicationEvent(ApplicationReadyEvent ready) {
            if (!started.compareAndSet(false, true)) return;
            notifications.subscribe(kind -> dispatcher.wake());
            tasks.addFixedDelayTask(new FixedDelayTask(dispatcher::wake, period, Duration.ZERO));
            tasks.afterPropertiesSet();
        }

        @Override
        public void destroy() {
            tasks.destroy();
        }
    }
}
