package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Pins the operational contract on {@code hephaestus.mentor.*}: defaults are the production
 * tuning documented on the record, and overrides bind through the prefix.
 */
class InteractiveSandboxPropertiesTest extends BaseUnitTest {

    @Test
    void bindsDefaultsAndOverrides() {
        InteractiveSandboxProperties defaults = bind(Map.of());
        assertThat(defaults.idleTtlSeconds()).isEqualTo(900);
        assertThat(defaults.graceTimeoutSeconds()).isEqualTo(25);
        assertThat(defaults.maxSessionsPerUser()).isEqualTo(3);
        assertThat(defaults.maxFrameChars()).isEqualTo(1_048_576);

        InteractiveSandboxProperties overridden =
                bind(Map.of("hephaestus.mentor.idle-ttl-seconds", "60", "hephaestus.mentor.max-frame-chars", "16384"));
        assertThat(overridden.idleTtlSeconds()).isEqualTo(60);
        assertThat(overridden.maxFrameChars()).isEqualTo(16_384);
        assertThat(overridden.ringBufferFrames()).isEqualTo(512); // unspecified → default holds
    }

    private static InteractiveSandboxProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bindOrCreate("hephaestus.mentor", InteractiveSandboxProperties.class);
    }
}
