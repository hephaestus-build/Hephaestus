package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryProperties;
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
 * Pre-pulls the agent and Git preparation images on startup, so the first review and the first Git
 * operation do not spend their deadlines on a pull. Part of the worker capability (the Docker
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
    private final GitRepositoryProperties git;
    private final MeterRegistry meterRegistry;
    private final AgentImageContractVerifier contractVerifier;

    public AgentImagePullBootstrapper(
            DockerImageOperations imageOps,
            AgentImageProperties properties,
            GitRepositoryProperties git,
            MeterRegistry meterRegistry,
            AgentImageContractVerifier contractVerifier) {
        this.imageOps = imageOps;
        this.properties = properties;
        this.git = git;
        this.meterRegistry = meterRegistry;
        this.contractVerifier = contractVerifier;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void pullOnStartup() {
        pull(properties.reference());
        contractVerifier.verify(properties.reference());
        pull(git.image());
    }

    private void pull(String image) {
        ImagePullBootstrapperSupport.applyPolicy(
                image,
                properties.pullPolicy(),
                imageOps,
                AgentMetrics.AGENT_IMAGE_PULL_DURATION,
                AgentMetrics.AGENT_IMAGE_PULL_FAILURE,
                AgentMetrics.AGENT_IMAGE_PULL_SKIPPED,
                meterRegistry,
                log);
    }
}
