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
 * Two env-var names reach each CONFIG feature flag, and both have to keep working: the long
 * {@code HEPHAESTUS_FEATURES_FLAGS_*} form that Boot's relaxed binding resolves into the flag map, and
 * the short name that {@code application.yml} reads through a placeholder — {@code GITLAB_WORKSPACE_CREATION},
 * which is the name the operator docs, {@code docker/.env} and the compose files all use.
 *
 * <p>The short name is only real because {@code application.yml} carries the placeholder — a
 * Compose-file alias would leave it doing nothing anywhere else. These tests bind the shipped
 * {@code application.yml} for real, through a real {@link SystemEnvironmentPropertySource}, so neither
 * route can quietly stop working.
 */
class FeatureFlagEnvBindingTest extends BaseUnitTest {

    @Test
    void gitlabWorkspaceCreationIsSettableByItsDocumentedEnvVar() throws Exception {
        FeatureProperties properties = bindWith(Map.of("GITLAB_WORKSPACE_CREATION", "true"));

        assertThat(properties.isEnabled("gitlab-workspace-creation"))
                .as("GITLAB_WORKSPACE_CREATION must reach the flag the code reads, in every deployment shape")
                .isTrue();
    }

    @Test
    void bothFlagsAreOffWhenNothingIsSet() throws Exception {
        FeatureProperties properties = bindWith(Map.of());

        assertThat(properties.isEnabled("gitlab-workspace-creation")).isFalse();
        assertThat(properties.isEnabled("page-experience-metrics")).isFalse();
    }

    @Test
    void pageExperienceMetricsIsSettableByItsDocumentedEnvVar() throws Exception {
        FeatureProperties properties = bindWith(Map.of("PAGE_EXPERIENCE_METRICS", "true"));

        assertThat(properties.isEnabled("page-experience-metrics")).isTrue();
    }

    /**
     * Adding the short-name placeholder must not have cost anyone the long name: a Kubernetes or Compose
     * deployment that already sets {@code HEPHAESTUS_FEATURES_FLAGS_GITLAB_WORKSPACE_CREATION} directly
     * keeps working, and keeps winning over the placeholder's default, because an env var outranks a
     * config file.
     */
    @Test
    void theLongHephaestusFeaturesFlagsSpellingStillReachesTheSameFlag() throws Exception {
        FeatureProperties properties = bindWith(Map.of("HEPHAESTUS_FEATURES_FLAGS_GITLAB_WORKSPACE_CREATION", "true"));

        assertThat(properties.isEnabled("gitlab-workspace-creation")).isTrue();
    }

    @Test
    void theLongSpellingWinsOverTheShortNamesPlaceholder() throws Exception {
        FeatureProperties properties = bindWith(Map.of(
                "HEPHAESTUS_FEATURES_FLAGS_GITLAB_WORKSPACE_CREATION", "true", "GITLAB_WORKSPACE_CREATION", "false"));

        assertThat(properties.isEnabled("gitlab-workspace-creation"))
                .as("an env var outranks the config file the placeholder lives in")
                .isTrue();
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
