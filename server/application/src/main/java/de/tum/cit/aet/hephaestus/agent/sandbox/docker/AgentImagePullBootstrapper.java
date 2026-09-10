package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Pre-pulls the agent container image on startup. Part of the worker capability (the Docker
 * sandbox), so it shares the worker-role gate with {@code DockerSandboxConfiguration} — present
 * in the monolith ({@code matchIfMissing=true}), absent on non-worker pods.
 * Artifact generation does not run containers and must not contact the Docker registry.
 */
@Component
@Profile("!specs & !cds-training")
@ConditionalOnProperty(name = RuntimeRole.WORKER_PROPERTY, havingValue = "true", matchIfMissing = true)
public class AgentImagePullBootstrapper {

    private static final Logger log = LoggerFactory.getLogger(AgentImagePullBootstrapper.class);

    private final DockerImageOperations imageOps;
    private final AgentImageProperties properties;
    private final MeterRegistry meterRegistry;
    private final AgentImageContractVerifier contractVerifier;

    public AgentImagePullBootstrapper(
            DockerImageOperations imageOps,
            AgentImageProperties properties,
            MeterRegistry meterRegistry,
            AgentImageContractVerifier contractVerifier) {
        this.imageOps = imageOps;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.contractVerifier = contractVerifier;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void pullOnStartup() {
        ImagePullBootstrapperSupport.applyPolicy(
                properties.reference(),
                properties.pullPolicy(),
                imageOps,
                AgentMetrics.AGENT_IMAGE_PULL_DURATION,
                AgentMetrics.AGENT_IMAGE_PULL_FAILURE,
                AgentMetrics.AGENT_IMAGE_PULL_SKIPPED,
                meterRegistry,
                log);
        contractVerifier.verify(properties.reference());
    }
}
