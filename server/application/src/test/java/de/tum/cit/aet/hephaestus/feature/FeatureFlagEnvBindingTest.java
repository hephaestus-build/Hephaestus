package de.tum.cit.aet.hephaestus.feature;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Two env-var names reach each CONFIG feature flag: the long {@code HEPHAESTUS_FEATURES_FLAGS_*} form that
 * Boot's relaxed binding resolves into the flag map, and the short name that {@code application.yml} reads
 * through a placeholder. These tests bind the shipped {@code application.yml} through a real
 * {@link SystemEnvironmentPropertySource}, so neither route can quietly stop working.
 */
class FeatureFlagEnvBindingTest extends BaseUnitTest {

    @Test
    void pageExperienceMetricsIsSettableByItsDocumentedEnvVar() throws Exception {
        FeatureProperties properties = bindWith(Map.of("PAGE_EXPERIENCE_METRICS", "true"));

        assertThat(properties.isEnabled("page-experience-metrics")).isTrue();
    }

    @Test
    void pageExperienceMetricsIsOffWhenNothingIsSet() throws Exception {
        assertThat(bindWith(Map.of()).isEnabled("page-experience-metrics")).isFalse();
    }

    /** An env var outranks the config file that holds the short name's placeholder. */
    @Test
    void theLongSpellingWinsOverTheShortNamesPlaceholder() throws Exception {
        FeatureProperties properties = bindWith(Map.of(
                "HEPHAESTUS_FEATURES_FLAGS_PAGE_EXPERIENCE_METRICS", "true", "PAGE_EXPERIENCE_METRICS", "false"));

        assertThat(properties.isEnabled("page-experience-metrics")).isTrue();
    }

    private static FeatureProperties bindWith(Map<String, Object> envVars) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, envVars));
        List<PropertySource<?>> yaml =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        yaml.forEach(source -> environment.getPropertySources().addLast(source));

        return Binder.get(environment)
                .bind("hephaestus.features", FeatureProperties.class)
                .orElseThrow(() -> new AssertionError("application.yml no longer defines hephaestus.features"));
    }
}
