package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.job.AgentProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import java.time.Duration;
import org.springframework.boot.autoconfigure.context.LifecycleProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.core.env.Environment;

/** Keeps the gateway and database open for the review drain, before the web server's graceful shutdown. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAgentRuntime
@EnableConfigurationProperties(AgentProperties.class)
public class AgentShutdownConfiguration {
    public static final int DRAIN_PHASE = WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE + 1;
    private static final Duration SHUTDOWN_MARGIN = Duration.ofSeconds(55);

    public static Duration drainPhaseTimeout(Duration drain) {
        return drain.plus(SHUTDOWN_MARGIN);
    }

    @Bean(name = AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME)
    DefaultLifecycleProcessor lifecycleProcessor(AgentProperties properties, Environment environment) {
        Duration phaseTimeout = Binder.get(environment)
                .bind("spring.lifecycle.timeout-per-shutdown-phase", Duration.class)
                .orElseGet(() -> new LifecycleProperties().getTimeoutPerShutdownPhase());
        DefaultLifecycleProcessor processor = new DefaultLifecycleProcessor();
        processor.setTimeoutPerShutdownPhase(phaseTimeout.toMillis());
        processor.setTimeoutForShutdownPhase(
                DRAIN_PHASE, drainPhaseTimeout(properties.drainTimeout()).toMillis());
        return processor;
    }
}
