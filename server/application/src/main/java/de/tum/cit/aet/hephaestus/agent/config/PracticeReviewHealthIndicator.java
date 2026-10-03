package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.job.AgentProperties;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryProperties;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class PracticeReviewHealthIndicator implements HealthIndicator {

    private final AgentProperties agentProperties;
    private final GitRepositoryProperties gitProperties;
    private final ArtifactSourceCatalogRegistry sourceCatalog;
    private final Clock clock;
    private final boolean workerRole;

    public PracticeReviewHealthIndicator(
            AgentProperties agentProperties,
            GitRepositoryProperties gitProperties,
            ArtifactSourceCatalogRegistry sourceCatalog,
            Clock clock,
            @Value("${" + RuntimeRole.WORKER_PROPERTY + ":true}") boolean workerRole) {
        this.agentProperties = agentProperties;
        this.gitProperties = gitProperties;
        this.sourceCatalog = sourceCatalog;
        this.clock = clock;
        this.workerRole = workerRole;
    }

    @Override
    public Health health() {
        if (!workerRole || !agentProperties.enabled()) {
            return Health.up().withDetail("reviewsEnabled", false).build();
        }
        if (!gitProperties.enabled()) {
            return Health.outOfService()
                    .withDetail("reviewsEnabled", true)
                    .withDetail("reason", "GIT_CHECKOUT_DISABLED")
                    .build();
        }
        if (sourceCatalog
                .earliestUseDecisionExpiry(SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW)
                .filter(expiry -> !expiry.isAfter(clock.instant()))
                .isPresent()) {
            return Health.outOfService()
                    .withDetail("reviewsEnabled", true)
                    .withDetail("reason", "SOURCE_AUTHORIZATION_EXPIRED")
                    .build();
        }
        return Health.up().withDetail("reviewsEnabled", true).build();
    }
}
