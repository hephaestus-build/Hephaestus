package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJobExecutor;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.DefaultLifecycleProcessor;

/**
 * Binds {@link WorkerProperties} whenever the worker role is enabled — deliberately NOT also gated on
 * the WSS endpoint the way {@link WorkerConfiguration} is. Worker identity drives job ownership and
 * orphan recovery, which a worker without a control channel still needs.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWorkerRole
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerPropertiesConfiguration {

    /**
     * What a shutdown phase may take beyond the drain: settling the jobs left (15 s) and the shared deadline for
     * stopping their containers.
     */
    static final Duration SHUTDOWN_MARGIN = ReviewJobExecutor.DRAIN_CLEANUP_TIMEOUT.plusSeconds(15);

    /**
     * Gives every shutdown phase the drain timeout plus {@link #SHUTDOWN_MARGIN}, so the drain is the worker's only
     * shutdown setting. A fixed phase timeout below the drain would let Spring close the database under running
     * reviews. It replaces Spring Boot's processor, which reads {@code spring.lifecycle.timeout-per-shutdown-phase}.
     */
    @Bean(name = AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME)
    DefaultLifecycleProcessor lifecycleProcessor(WorkerProperties properties) {
        DefaultLifecycleProcessor processor = new DefaultLifecycleProcessor();
        processor.setTimeoutPerShutdownPhase(
                properties.drain().timeout().plus(SHUTDOWN_MARGIN).toMillis());
        return processor;
    }
}
