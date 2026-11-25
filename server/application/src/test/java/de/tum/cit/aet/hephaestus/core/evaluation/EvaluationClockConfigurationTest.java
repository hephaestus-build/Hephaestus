package de.tum.cit.aet.hephaestus.core.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.aet.hephaestus.core.ControlledClock;
import de.tum.cit.aet.hephaestus.core.TimeConfig;
import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@Tag("unit")
class EvaluationClockConfigurationTest {

    private static final String ENABLED = "hephaestus.evaluation.controlled-clock.enabled=true";

    @Test
    void shouldKeepTheSystemClockWhenTheServerRoleIsDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(TimeConfig.class, EvaluationConfiguration.class)
                .withPropertyValues(ENABLED, "hephaestus.runtime.server.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(Clock.class).doesNotHaveBean(ControlledClock.class);
                    assertThat(context.getBean(Clock.class)).isEqualTo(Clock.systemUTC());
                });
    }

    @Test
    void shouldLeaveBothRoutesUnregisteredOnTheProductionProfileEvenWithTheFlagOn() {
        new WebApplicationContextRunner()
                .withUserConfiguration(
                        WebConfig.class, TimeConfig.class, EvaluationController.class, EvaluationConfiguration.class)
                .withPropertyValues(ENABLED, "spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context)
                            .hasSingleBean(Clock.class)
                            .doesNotHaveBean(EvaluationController.class)
                            .doesNotHaveBean(EvaluationProperties.class);
                    assertThat(context.getBean(Clock.class)).isEqualTo(Clock.systemUTC());
                    var mvc = MockMvcBuilders.webAppContextSetup(context).build();
                    mvc.perform(post("/internal/evaluation/clock")).andExpect(status().isNotFound());
                    mvc.perform(post("/internal/evaluation/settle")).andExpect(status().isNotFound());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @ConfigurationPropertiesScan(basePackageClasses = EvaluationProperties.class)
    static class WebConfig {}

    @Test
    void shouldRefuseToStartWhenAProviderCredentialIsConfigured() {
        new ApplicationContextRunner()
                .withUserConfiguration(TimeConfig.class, EvaluationConfiguration.class)
                .withBean(AuthProperties.class, () -> mock(AuthProperties.class))
                .withPropertyValues(ENABLED, "hephaestus.workspace.default.token=ghp_fixture_for_refusal")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseMessage("Controlled clock cannot run with a provider credential:"
                                + " hephaestus.workspace.default.token"));
    }

    @Test
    void shouldShareTheControlledInstantAcrossTimeZones() {
        var clock = new ControlledClock(Instant.EPOCH);
        var zone = ZoneOffset.ofHours(2);
        Clock sameTime = clock.withZone(zone);

        clock.set(Instant.EPOCH.plusSeconds(1));

        assertThat(sameTime.getZone()).isEqualTo(zone);
        assertThat(sameTime.instant()).isEqualTo(clock.instant());
    }
}
