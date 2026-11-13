package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class CommitIdentityResolutionDefaultsTest extends BaseUnitTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Defaults.class)
            .withBean(CommitIdentityResolutionRepository.class, () -> mock(CommitIdentityResolutionRepository.class))
            .withBean(CommitIdentityResolutionService.class, () -> mock(CommitIdentityResolutionService.class))
            .withBean(RepositoryScopeProvider.class, () -> mock(RepositoryScopeProvider.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void shouldEnableTheJobWithoutAnInstanceSwitch() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CommitIdentityResolutionJob.class);
            assertThat(context.getBean(CommitIdentityResolutionProperties.class).enabled())
                    .isTrue();
            assertThat(context.getBean(CommitIdentityResolutionProperties.class).batchSize())
                    .isEqualTo(50);
        });
    }

    @Test
    void shouldKeepTheExplicitOffSwitch() {
        runner.withPropertyValues("hephaestus.commit-identity-resolution.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CommitIdentityResolutionJob.class);
                    assertThat(context.getBean(CommitIdentityResolutionProperties.class)
                                    .enabled())
                            .isFalse();
                });
    }

    @Test
    void shouldKeepTheJobOutOfANonServerRuntime() {
        runner.withPropertyValues(RuntimeRole.SERVER_PROPERTY + "=false")
                .run(context -> assertThat(context).doesNotHaveBean(CommitIdentityResolutionJob.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"specs", "cds-training"})
    void shouldKeepBuildProfilesFromReadingTheRepairQueue(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertThat(context).doesNotHaveBean(CommitIdentityResolutionJob.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CommitIdentityResolutionProperties.class)
    @Import(CommitIdentityResolutionJob.class)
    static class Defaults {}
}
