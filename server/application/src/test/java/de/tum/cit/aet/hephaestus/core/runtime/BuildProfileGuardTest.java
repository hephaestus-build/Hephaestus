package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.mock.env.MockEnvironment;

class BuildProfileGuardTest extends BaseUnitTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.import=")
            .withUserConfiguration(BuildProfileGuard.class);

    @Test
    void shouldBeDiscoveredByTheApplicationComponentScan() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "specs");
        var scanner = new ClassPathScanningCandidateComponentProvider(true, environment);
        assertThat(scanner.findCandidateComponents(BuildProfileGuard.class.getPackageName()))
                .extracting(BeanDefinition::getBeanClassName)
                .contains(BuildProfileGuard.class.getName());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "prod,specs", "prod,cds-training",
                "worker-node,specs", "worker-node,cds-training",
                "webhook-server,specs", "webhook-server,cds-training"
            })
    void shouldRejectProductionBuildProfileCombinationsEvenThroughGroups(String profiles) {
        runner.withPropertyValues("spring.profiles.active=" + profiles).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                            "The specs and cds-training profiles are build-only and cannot be combined with production.");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "worker-node", "webhook-server", "specs", "cds-training", "test"})
    void shouldAllowRuntimeAndArtifactProfilesSeparately(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertThat(context).hasNotFailed());
    }
}
