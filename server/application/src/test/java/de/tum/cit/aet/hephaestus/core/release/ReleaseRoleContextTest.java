package de.tum.cit.aet.hephaestus.core.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

@Tag("unit")
class ReleaseRoleContextTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReleaseProperties.class)
    static class PropertiesConfiguration {}

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(Clock.class, Clock::systemUTC)
            .withPropertyValues("spring.application.version=1.2.3")
            .withUserConfiguration(
                    PropertiesConfiguration.class,
                    ValidationAutoConfiguration.class,
                    RunningRelease.class,
                    ReleaseCheckClient.class,
                    ReleaseCheckService.class,
                    ReleaseAdminController.class);

    @Test
    void shouldKeepIdentityButNotDiscoveryWhenServerRoleIsDisabled() {
        context.run(application -> assertThat(application)
                .hasNotFailed()
                .hasSingleBean(ReleaseCheckService.class)
                .hasSingleBean(ReleaseAdminController.class));
        context.withPropertyValues("hephaestus.runtime.server.enabled=false")
                .run(application -> assertThat(application)
                        .hasNotFailed()
                        .hasSingleBean(RunningRelease.class)
                        .doesNotHaveBean(ReleaseCheckClient.class)
                        .doesNotHaveBean(ReleaseCheckService.class)
                        .doesNotHaveBean(ReleaseAdminController.class));
    }

    @Test
    void shouldAcceptAnyRegistryButRefuseAMalformedLockIdentity() {
        context.withPropertyValues(
                        "hephaestus.release.commit=" + ReleaseFixtures.COMMIT,
                        "hephaestus.release.image=registry.internal:5000/hephaestus/application-server@sha256:"
                                + "c".repeat(64))
                .run(application -> assertThat(application).hasNotFailed());
        context.withPropertyValues("hephaestus.release.commit=abc")
                .run(application -> assertThat(application).hasFailed());
        context.withPropertyValues("hephaestus.release.image=ghcr.io/hephaestus-build/application-server:1.2.3")
                .run(application -> assertThat(application).hasFailed());
    }
}
