package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InteractiveSandboxSpecTest extends BaseUnitTest {

    private static InteractiveSandboxSpec spec(Map<String, String> env) {
        return new InteractiveSandboxSpec(
                UUID.randomUUID(),
                "u1",
                "w1",
                "ghcr.io/example/agent:latest",
                List.of("node", "/run.ts"),
                env,
                new NetworkPolicy(true, null, null),
                new ResourceLimits(256 * 1024 * 1024, 0.5, 64, Duration.ofMinutes(1)),
                SecurityProfile.DEFAULT,
                Map.of());
    }

    @ParameterizedTest(name = "accepts well-formed env key: {0}")
    @ValueSource(strings = {"FOO", "FOO_BAR", "_LEADING_UNDERSCORE", "x", "MIXED_case_42", "A0"})
    void acceptsValidKeys(String key) {
        assertThatNoException().isThrownBy(() -> spec(Map.of(key, "v")));
    }

    @ParameterizedTest(name = "rejects ill-formed env key: {0}")
    @ValueSource(
            strings = {"", " ", "0LEADING_DIGIT", "with space", "FOO-BAR", "FOO.BAR", "--privileged", "-it", "=val"})
    void rejectsInvalidKeys(String key) {
        assertThatThrownBy(() -> spec(Map.of(key, "v")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid env var name");
    }

    @Test
    void rejectsNulInValue() {
        assertThatThrownBy(() -> spec(Map.of("FOO", "a\0b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NUL");
    }

    @ParameterizedTest(name = "rejects unsafe control char in env value: {0}")
    @ValueSource(strings = {"a\nb", "a\rb"})
    void rejectsLfCrInValue(String value) {
        assertThatThrownBy(() -> spec(Map.of("FOO", value)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NUL/LF/CR");
    }

    @Test
    void environmentIsCopied() {
        Map<String, String> env = new HashMap<>();
        env.put("OK_KEY", "good");
        InteractiveSandboxSpec s = spec(env);
        env.put("LD_PRELOAD", "/tmp/evil.so");
        env.remove("OK_KEY");
        assertThat(s.environment()).containsEntry("OK_KEY", "good").doesNotContainKey("LD_PRELOAD");
    }

    @Test
    void commandIsCopied() {
        List<String> cmd = new ArrayList<>(List.of("node", "/run.ts"));
        InteractiveSandboxSpec s = new InteractiveSandboxSpec(
                UUID.randomUUID(),
                "u1",
                "w1",
                "ghcr.io/example/agent:latest",
                cmd,
                Map.of(),
                new NetworkPolicy(true, null, null),
                new ResourceLimits(256 * 1024 * 1024, 0.5, 64, Duration.ofMinutes(1)),
                SecurityProfile.DEFAULT,
                Map.of());
        cmd.set(0, "/bin/sh");
        cmd.add("-c");
        cmd.add("rm -rf /");
        assertThat(s.command()).containsExactly("node", "/run.ts");
    }
}
