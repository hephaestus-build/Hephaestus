package de.tum.cit.aet.hephaestus.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.AgentProperties;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class PracticeReviewHealthIndicatorTest extends BaseUnitTest {

    @Test
    void shouldRefuseReadinessWhenReviewWorkerHasNoCheckout() {
        var health = indicator(true, false, Optional.empty()).health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsEntry("reason", "GIT_CHECKOUT_DISABLED");
    }

    @Test
    void shouldStayHealthyWhenReviewsAreDisabled() {
        assertThat(indicator(false, false, Optional.empty()).health().getStatus())
                .isEqualTo(Status.UP);
    }

    @Test
    void shouldStayHealthyOnANonWorkerRole() {
        assertThat(indicator(true, false, Optional.empty(), false).health().getStatus())
                .isEqualTo(Status.UP);
    }

    @Test
    void shouldRefuseReadinessWhenSourceAuthorizationExpired() {
        var health = indicator(true, true, Optional.of(Instant.parse("2025-01-01T00:00:00Z")))
                .health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsEntry("reason", "SOURCE_AUTHORIZATION_EXPIRED");
    }

    private static PracticeReviewHealthIndicator indicator(
            boolean agentEnabled, boolean gitEnabled, Optional<Instant> expiry) {
        return indicator(agentEnabled, gitEnabled, expiry, true);
    }

    private static PracticeReviewHealthIndicator indicator(
            boolean agentEnabled, boolean gitEnabled, Optional<Instant> expiry, boolean workerRole) {
        ArtifactSourceCatalogRegistry sourceCatalog = mock(ArtifactSourceCatalogRegistry.class);
        expiry.ifPresent(
                value -> when(sourceCatalog.earliestUseDecisionExpiry(SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                        .thenReturn(Optional.of(value)));
        return new PracticeReviewHealthIndicator(
                agent(agentEnabled),
                git(gitEnabled),
                sourceCatalog,
                java.time.Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                workerRole);
    }

    private static AgentProperties agent(boolean enabled) {
        return new AgentProperties(
                enabled, Duration.ofSeconds(1), 5, 5, Duration.ofSeconds(25), Duration.ofDays(14), Duration.ofDays(90));
    }

    private static GitRepositoryProperties git(boolean enabled) {
        return new GitRepositoryProperties(enabled, 2, "ghcr.io/hephaestus-build/git-preparation:test", 1L << 33);
    }
}
