package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJobExecutor;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import java.time.Duration;
import org.springframework.boot.autoconfigure.context.LifecycleProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.core.env.Environment;

/** Binds {@link WorkerProperties} whenever the worker role is enabled: worker identity drives job ownership. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWorkerRole
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerPropertiesConfiguration {

    /**
     * What the drain phase may take beyond the drain timeout: joining the poll thread before the drain, settling the
     * jobs left (15 s), and the shared deadline for stopping their containers.
     */
    static final Duration SHUTDOWN_MARGIN = ReviewJobExecutor.POLL_THREAD_JOIN_TIMEOUT
            .plus(ReviewJobExecutor.DRAIN_CLEANUP_TIMEOUT)
            .plusSeconds(15);

    /** How long the phase that drains reviews and Heph turns may take. */
    public static Duration drainPhaseTimeout(Duration drain) {
        return drain.plus(SHUTDOWN_MARGIN);
    }

    /**
     * Gives the drain phase the drain timeout plus {@link #SHUTDOWN_MARGIN}, so the drain is the worker's only drain
     * setting, and leaves every other phase at {@code spring.lifecycle.timeout-per-shutdown-phase}. It replaces Spring
     * Boot's processor, which gives every phase that one timeout: below the drain, Spring would close the database
     * under running reviews; equal to it, a stuck request after the drain could hold the web server as long.
     */
    @Bean(name = AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME)
    DefaultLifecycleProcessor lifecycleProcessor(WorkerProperties properties, Environment environment) {
        Duration phaseTimeout = Binder.get(environment)
                .bind("spring.lifecycle.timeout-per-shutdown-phase", Duration.class)
                .orElseGet(() -> new LifecycleProperties().getTimeoutPerShutdownPhase());
        DefaultLifecycleProcessor processor = new DefaultLifecycleProcessor();
        processor.setTimeoutPerShutdownPhase(phaseTimeout.toMillis());
        processor.setTimeoutForShutdownPhase(
                WorkerDrainCoordinator.PHASE,
                drainPhaseTimeout(properties.drain().timeout()).toMillis());
        return processor;
    }
}
