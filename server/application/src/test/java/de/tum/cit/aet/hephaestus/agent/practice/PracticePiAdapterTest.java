package de.tum.cit.aet.hephaestus.agent.practice;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PracticePiAdapterTest extends BaseUnitTest {

    private static final String IMAGE = "ghcr.io/hephaestus-build/agent-pi:0.73.2";
    private PracticePiAdapter adapter;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        adapter = new PracticePiAdapter(
                new PiRuntimeFactory(mapper),
                new PiResultParser(mapper, metrics),
                new AgentImageProperties(IMAGE, ImagePullPolicy.IF_NOT_PRESENT));
    }

    private PracticeAgentRequest proxyRequest() {
        return new PracticeAgentRequest(
                "azure-openai-responses", "gpt-5.4-mini", null, null, false, "job-token-123", false, 600);
    }

    @Test
    void buildsSpecWithImage() {
        var spec = adapter.buildSandboxSpec(proxyRequest());
        assertThat(spec.image()).isEqualTo(IMAGE);
    }

    @Test
    void shouldResolvePrecomputeInputsFromTaskInsteadOfShellPaths() {
        assertThat(PracticePiAdapter.buildPrecomputeStep())
                .contains("/workspace/pi-precompute.ts /workspace")
                .contains("ln -sf /opt/precompute/lib /workspace/work/precompute-stage/lib")
                .doesNotContain("inputs/", "--repo", "--context", "sed ");
    }

    @Test
    void shouldKeepPrecomputeBestEffortAndCredentialFree() {
        assertThat(PracticePiAdapter.buildPrecomputeStep())
                .contains("env -i HOME=/home/agent PATH=/usr/local/bin:/usr/bin:/bin TMPDIR=/tmp node")
                .contains("--permission", "--allow-fs-read=/workspace", "--allow-fs-read=/opt/precompute")
                .contains("--allow-fs-write=/workspace/work/precompute-stage*")
                .contains("--allow-fs-write=/workspace/work/precompute-out*")
                .contains("|| {", "; true; }");
    }

    @Test
    void networkPolicyContract() {
        var spec = adapter.buildSandboxSpec(proxyRequest());
        var networkPolicy = spec.networkPolicy();
        org.junit.jupiter.api.Assertions.assertNotNull(networkPolicy);
        assertThat(networkPolicy.llmProxyToken()).isEqualTo("job-token-123");
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
                "openai-completions", "gpt-oss-120b", 131072, 4096, true, "job-token-123", false, 600);

        var spec = adapter.buildSandboxSpec(request);

        assertThat(spec.image()).isEqualTo(IMAGE);
        var networkPolicy = spec.networkPolicy();
        org.junit.jupiter.api.Assertions.assertNotNull(networkPolicy);
        assertThat(networkPolicy.llmProxyToken()).isEqualTo("job-token-123");
    }
}
