package de.tum.cit.aet.hephaestus.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Binds {@code hephaestus.agent.image} out of the real {@code application.yml}.
 *
 * <p>The fallback reference is the whole point of ADR 0031, and it is a YAML expression rather than
 * a Java constant — so only binding it proves it resolves, and proves it resolves to <em>this</em>
 * deployment's tag rather than to a release channel.
 */
class AgentImageDefaultResolutionTest extends BaseUnitTest {

    private static AgentImageProperties bindWith(Map<String, Object> overrides) throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
        List<PropertySource<?>> documents =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        documents.forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment)
                .bind("hephaestus.agent.image", AgentImageProperties.class)
                .get();
    }

    @Test
    void shouldFollowTheImageTagTheDeploymentIsRunning() throws IOException {
        assertThat(bindWith(Map.of("APP_VERSION", "1.2.3")).reference())
                .isEqualTo("ghcr.io/hephaestus-build/agent:1.2.3");
    }

    @Test
    void shouldFollowACommitShaDeploy() throws IOException {
        String sha = "9a1f0c2e1b7d4a6f8c3e5b2d9a7f4c1e0b8d6a35";
        assertThat(bindWith(Map.of("APP_VERSION", sha)).reference()).isEqualTo("ghcr.io/hephaestus-build/agent:" + sha);
    }

    @Test
    void shouldNeverFallBackToAReleaseChannel() throws IOException {
        assertThat(bindWith(Map.of()).reference()).doesNotEndWith(":latest");
    }

    /**
     * A deploy substrate that interpolates an unset image tag to the empty string supplies
     * {@code APP_VERSION} as present-but-empty, and a present placeholder value defeats its own
     * default — so the derivation yields a tagless reference rather than the development fallback.
     * {@code AgentImageReferenceGuard} is what stops it, which is why this asserts the shape rather
     * than assuming the placeholder recovers.
     */
    @Test
    void shouldNotRecoverFromAnEmptyImageTag() throws IOException {
        assertThat(bindWith(Map.of("APP_VERSION", "")).reference()).isEqualTo("ghcr.io/hephaestus-build/agent:");
    }
}
