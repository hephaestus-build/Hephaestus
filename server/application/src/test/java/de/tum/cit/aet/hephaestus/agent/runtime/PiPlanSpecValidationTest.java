package de.tum.cit.aet.hephaestus.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.practice.PracticeRunnerProfile;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PiPlanSpecValidationTest extends BaseUnitTest {

    private static final PiRunnerProfile PROFILE = new PracticeRunnerProfile();

    @Test
    void jobTokenIsAlwaysRequired() {
        assertThatThrownBy(() ->
                        new PiPlanSpec("openai-completions", "gpt-5.4-mini", null, null, null, null, 600, PROFILE, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jobToken");
    }

    @Test
    void blankJobTokenRejected() {
        assertThatThrownBy(() ->
                        new PiPlanSpec("openai-completions", "gpt-5.4-mini", null, null, null, "  ", 600, PROFILE, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jobToken");
    }

    @Test
    void blankApiProtocolRejected() {
        assertThatThrownBy(() -> new PiPlanSpec("", "gpt-5.4-mini", null, null, null, "job-token", 600, PROFILE, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiProtocol");
    }

    @Test
    void blankUpstreamModelIdRejected() {
        assertThatThrownBy(() ->
                        new PiPlanSpec("openai-completions", "  ", null, null, null, "job-token", 600, PROFILE, ""))
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
                        PiRuntimeFactory.TIMEOUT_BUFFER_SECONDS,
                        PROFILE,
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
                        600,
                        BLANK_SCRIPT_PROFILE,
                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runnerScript");
    }

    @Test
    void nullPrecomputeStepNormalizesToEmptyString() {
        PiPlanSpec spec =
                new PiPlanSpec("openai-completions", "gpt-5.4-mini", null, null, null, "job-token", 600, PROFILE, null);

        // The null-coalesce protects PiRuntimeFactory's string concat from an NPE downstream.
        assertThat(spec.precomputeStep()).isEmpty();
    }

    @Test
    void azureProtocolWithTokenConstructs() {
        assertThatNoException()
                .isThrownBy(() -> new PiPlanSpec(
                        "azure-openai-responses", "gpt-5.4-mini", null, null, null, "job-token", 600, PROFILE, ""));
    }
}
