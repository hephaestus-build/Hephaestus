package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class E2eProfileOverlayTest extends BaseUnitTest {

    private static final String OVERLAY = "application-e2e.yml";

    @Test
    void shouldEnableLocalAgentJobsByDefault() throws IOException {
        var environment = new MockEnvironment();
        environment.getPropertySources().addLast(overlay());
        assertThat(environment.getProperty("hephaestus.agent.enabled", Boolean.class))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldHonorTheExplicitAgentSetting(boolean enabled) throws IOException {
        var environment = new MockEnvironment().withProperty("AGENT_ENABLED", Boolean.toString(enabled));
        environment.getPropertySources().addLast(overlay());
        assertThat(environment.getProperty("hephaestus.agent.enabled", Boolean.class))
                .isEqualTo(enabled);
    }

    @Test
    void usesUnfilteredSyncForE2e() throws IOException {
        PropertySource<?> overlay = overlay();

        assertThat(overlay.getProperty("hephaestus.sync.filters.allowed-organizations"))
                .isEqualTo("");
    }

    private static PropertySource<?> overlay() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(OVERLAY, new ClassPathResource(OVERLAY));
        assertThat(sources).hasSize(1);
        return sources.get(0);
    }
}
