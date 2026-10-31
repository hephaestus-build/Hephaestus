package de.tum.cit.aet.hephaestus.feature;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.MockSecurityContextUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;

class FeatureFlagConfigTest extends BaseUnitTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FeatureFlagConfiguration.class)
            .withPropertyValues("hephaestus.features.flags.page-experience-metrics=true");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldExposeEnabledConfigFlagWhenPropertyIsTrue() {
        SecurityContextHolder.setContext(
                MockSecurityContextUtils.createSecurityContext("user", "user-id", new String[0], "mock-token"));

        contextRunner.run(context -> {
            FeatureFlagsDTO response = FeatureFlagsDTO.from(context.getBean(FeatureFlagService.class));

            assertThat(response.PAGE_EXPERIENCE_METRICS()).isTrue();
            assertThat(response.ADMIN()).isFalse();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FeatureProperties.class)
    static class FeatureFlagConfiguration {

        @Bean
        FeatureFlagService featureFlagService(FeatureProperties properties) {
            return new FeatureFlagService(properties);
        }
    }
}
