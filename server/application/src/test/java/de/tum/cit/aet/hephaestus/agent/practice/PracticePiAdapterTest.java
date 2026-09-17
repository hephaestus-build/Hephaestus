package de.tum.cit.aet.hephaestus.agent.practice;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PracticePiAdapterTest extends BaseUnitTest {

    private static final String IMAGE = "ghcr.io/hephaestus-build/agent-pi:0.73.2";
    private PracticePiAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = adapter(null);
    }

    private static PracticePiAdapter adapter(@Nullable Double samplingTemperature) {
        ObjectMapper mapper = new ObjectMapper();
        return new PracticePiAdapter(
                new PiRuntimeFactory(mapper),
                new PiResultParser(mapper, new SimpleMeterRegistry()),
                new AgentImageProperties(IMAGE, ImagePullPolicy.IF_NOT_PRESENT),
                new PracticeReviewProperties(false, 15, 5, false, samplingTemperature));
    }

    @Test
    void carriesTheSamplingTemperatureOnlyWhenTheOperatorSetOne() {
        assertThat(adapter.buildSandboxSpec(proxyRequest()).environment())
                .doesNotContainKey(PracticePiAdapter.SAMPLING_TEMPERATURE_ENV);
        assertThat(adapter(0.2).buildSandboxSpec(proxyRequest()).environment())
                .containsEntry(PracticePiAdapter.SAMPLING_TEMPERATURE_ENV, "0.2");
    }

    private PracticeAgentRequest proxyRequest() {
        return new PracticeAgentRequest(
                "azure-openai-responses", "gpt-5.4-mini", null, null, false, "job-token-123", 600);
    }

    @Test
    void buildsSpecWithImage() {
        var spec = adapter.buildSandboxSpec(proxyRequest());
        assertThat(spec.image()).isEqualTo(IMAGE);
    }

    @Test
    void shouldDeriveTheChangeBeforePrecomputeAndStageBothWithTheRunner() {
        var spec = adapter.buildSandboxSpec(proxyRequest());
        assertThat(spec.inputFiles())
                .containsKeys("pi-change.ts", "pi-precompute.sh", "pi-precompute.ts", "pi-task-paths.ts");
        assertThat(spec.command())
                .anySatisfy(command -> assertThat(command)
                        .contains("node /workspace/pi-change.ts && sh /workspace/pi-precompute.sh 30 && "));
    }

    @Test
    void shouldCapPrecomputeAtThirtySecondsAndOneTenthOfTheJobDeadline() {
        assertThat(PracticePiAdapter.buildPrecomputeStep(600))
                .isEqualTo("node /workspace/pi-change.ts && sh /workspace/pi-precompute.sh 30 && ");
        assertThat(PracticePiAdapter.buildPrecomputeStep(120)).contains("pi-precompute.sh 12 &&");
        assertThat(PracticePiAdapter.buildPrecomputeStep(61)).contains("pi-precompute.sh 6 &&");
    }

    @Test
    void shouldKeepPracticeNetworkIsolatedBehindTheLlmProxy() {
        var policy = adapter.buildSandboxSpec(proxyRequest()).networkPolicy();
        org.junit.jupiter.api.Assertions.assertNotNull(policy);
        assertThat(policy.internetAccess()).isFalse();
        assertThat(policy.llmProxyToken()).isEqualTo("job-token-123");
    }

    @Test
    void outputPath() {
        assertThat(adapter.buildSandboxSpec(proxyRequest()).outputPath()).isEqualTo(SandboxLayout.OUTPUT_PATH);
    }

    @Test
    void doesNotInjectPromptFile() {
        var spec = adapter.buildSandboxSpec(proxyRequest());
        assertThat(spec.inputFiles()).doesNotContainKey(".prompt");
    }

    @Test
    void buildsWithCapabilityFields() {
        PracticeAgentRequest request = new PracticeAgentRequest(
                "openai-completions", "gpt-oss-120b", 131072, 4096, true, "job-token-123", 600);

        var spec = adapter.buildSandboxSpec(request);

        assertThat(spec.image()).isEqualTo(IMAGE);
        var networkPolicy = spec.networkPolicy();
        org.junit.jupiter.api.Assertions.assertNotNull(networkPolicy);
        assertThat(networkPolicy.llmProxyToken()).isEqualTo("job-token-123");
    }
}
