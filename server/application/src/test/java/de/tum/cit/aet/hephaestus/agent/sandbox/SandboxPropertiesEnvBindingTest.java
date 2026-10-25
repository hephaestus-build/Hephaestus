package de.tum.cit.aet.hephaestus.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.tum.cit.aet.hephaestus.agent.sandbox.docker.ContainerSecurityPolicy;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/** Binds the shipped {@code application.yml}, so the documented variables reach the limits a review sandbox gets. */
class SandboxPropertiesEnvBindingTest extends BaseUnitTest {

    @Test
    void shouldGiveReviewSandboxesTheLimitsTheDocumentedVariablesSet() throws IOException {
        var limits = bindWith(Map.of(
                        "SANDBOX_MEMORY_BYTES", "3221225472", "SANDBOX_CPUS", "1.5", "SANDBOX_PIDS_LIMIT", "300"))
                .defaultResourceLimits();

        assertThat(limits).isEqualTo(new SandboxProperties.DefaultResourceLimits(3221225472L, 1.5, 300));
    }

    @Test
    void shouldUseTheSameDefaultsInApplicationYamlAndInTheBindingWhenNothingIsSet() throws IOException {
        var withoutYaml = new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("hephaestus.sandbox", SandboxProperties.class)
                .defaultResourceLimits();

        assertThat(bindWith(Map.of()).defaultResourceLimits())
                .isEqualTo(withoutYaml)
                .isEqualTo(new SandboxProperties.DefaultResourceLimits(3L * 1024 * 1024 * 1024, 1.0, 256));
        // The shipped default keeps room for the processes when every tmpfs mount is full.
        assertThatCode(() -> ContainerSecurityPolicy.requireRoomBesideTmpfs(withoutYaml.memoryBytes()))
                .doesNotThrowAnyException();
    }

    private static SandboxProperties bindWith(Map<String, Object> envVars) throws IOException {
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
                .bind("hephaestus.sandbox", SandboxProperties.class)
                .orElseThrow(() -> new AssertionError("application.yml no longer defines hephaestus.sandbox"));
    }
}
