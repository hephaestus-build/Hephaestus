package de.tum.cit.aet.hephaestus.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.practice.PracticeRunnerProfile;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PiPlanSpecValidationTest extends BaseUnitTest {

    private static final PiRunnerProfile PROFILE = new PracticeRunnerProfile();
    private static final byte[] BYTES = new byte[] {0x01};

    private static PiPlanSpec specWith(Map<String, byte[]> extraInputs) {
        return new PiPlanSpec(
                "openai-completions",
                "gpt-5.4-mini",
                null,
                null,
                null,
                "job-token",
                true,
                600,
                PROFILE,
                extraInputs,
                "");
    }

    @Test
    void allowlistedPathAccepted() {
        assertThatNoException().isThrownBy(() -> specWith(Map.of(SandboxLayout.MENTOR_SYSTEM_PROMPT_PATH, BYTES)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"inputs/context/diff.patch", ".sessions/abc-123.jsonl"})
    void perTurnPathsRejected(String path) {
        assertThatThrownBy(() -> specWith(Map.of(path, BYTES))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void undeclaredPathRejected() {
        assertThatThrownBy(() -> specWith(Map.of("evil/../etc/passwd", BYTES)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evil/../etc/passwd")
                .hasMessageContaining("allowedExtraInputPaths");
    }

    @Test
    void jobTokenIsAlwaysRequired() {
        assertThatThrownBy(() -> new PiPlanSpec(
                        "openai-completions",
                        "gpt-5.4-mini",
                        null,
                        null,
                        null,
                        null,
                        false,
                        600,
                        PROFILE,
                        Map.of(),
                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jobToken");
    }

    @Test
    void blankJobTokenRejected() {
        assertThatThrownBy(() -> new PiPlanSpec(
                        "openai-completions",
                        "gpt-5.4-mini",
                        null,
                        null,
                        null,
                        "  ",
                        false,
                        600,
                        PROFILE,
                        Map.of(),
                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jobToken");
    }

    @Test
    void blankApiProtocolRejected() {
        assertThatThrownBy(() -> new PiPlanSpec(
                        "", "gpt-5.4-mini", null, null, null, "job-token", false, 600, PROFILE, Map.of(), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiProtocol");
    }

    @Test
    void blankUpstreamModelIdRejected() {
        assertThatThrownBy(() -> new PiPlanSpec(
                        "openai-completions", "  ", null, null, null, "job-token", false, 600, PROFILE, Map.of(), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upstreamModelId");
    }

    @Test
    void timeoutMustExceedBuffer() {
        assertThatThrownBy(() -> new PiPlanSpec(
                        "openai-completions",
                        "gpt-5.4-mini",
                        null,
                        null,
                        null,
                        "job-token",
                        true,
                        PiRuntimeFactory.TIMEOUT_BUFFER_SECONDS,
                        PROFILE,
                        Map.of(),
                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TIMEOUT_BUFFER_SECONDS");
    }

    /** A profile whose runner script is blank — the runnerScript() guard must reject it. */
    private static final PiRunnerProfile BLANK_SCRIPT_PROFILE = new PiRunnerProfile() {
        @Override
        public String runnerScript() {
            return "  ";
        }

        @Override
        public List<String> runtimeFlags() {
            return List.of();
        }

        @Override
        public Map<String, String> additionalEnv() {
            return Map.of();
        }
    };

    @Test
    void blankRunnerScriptRejected() {
        assertThatThrownBy(() -> new PiPlanSpec(
                        "openai-completions",
                        "gpt-5.4-mini",
                        null,
                        null,
                        null,
                        "job-token",
                        true,
                        600,
                        BLANK_SCRIPT_PROFILE,
                        Map.of(),
                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runnerScript");
    }

    @Test
    void nullPrecomputeStepNormalizesToEmptyString() {
        PiPlanSpec spec = new PiPlanSpec(
                "openai-completions",
                "gpt-5.4-mini",
                null,
                null,
                null,
                "job-token",
                true,
                600,
                PROFILE,
                Map.of(),
                null);

        // The null-coalesce protects PiRuntimeFactory's string concat from an NPE downstream.
        assertThat(spec.precomputeStep()).isEmpty();
    }

    @Test
    void azureProtocolWithTokenConstructs() {
        assertThatNoException()
                .isThrownBy(() -> new PiPlanSpec(
                        "azure-openai-responses",
                        "gpt-5.4-mini",
                        null,
                        null,
                        null,
                        "job-token",
                        false,
                        600,
                        PROFILE,
                        Map.of(),
                        ""));
    }

    @Test
    void traversalRejected() {
        // Only exact paths pass, so a key that merely starts like one never reaches the workspace manager.
        assertThatThrownBy(() -> specWith(Map.of("agent/mentor/system.md/../../../etc/passwd", BYTES)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
