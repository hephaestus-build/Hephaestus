package de.tum.cit.aet.hephaestus.agent.practice;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
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
                new PracticeReviewProperties(false, 15, 5, samplingTemperature, 12, 16000, 150_000),
                mapper);
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
                "azure-openai-responses", "gpt-5.4-mini", null, null, null, "job-token-123", 600, null, Map.of());
    }

    @Test
    void shouldHandTheRunnerTheWorkEachPracticeIsOwed() {
        assertThat(adapter.buildSandboxSpec(proxyRequest()).environment())
                .containsEntry(PracticePiAdapter.PRACTICE_MODEL_CALLS_ENV, "12")
                .containsEntry(PracticePiAdapter.PRACTICE_OUTPUT_TOKENS_ENV, "16000");
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
                        .contains("node /workspace/pi-change.ts && sh /workspace/pi-precompute.sh 60 150000 && "));
    }

    @Test
    void shouldStartTheReviewRunnerWithoutThePrecomputeToken() {
        String command = adapter.buildSandboxSpec(proxyRequest()).command().getLast();

        assertThat(command).contains("sh /workspace/pi-precompute.sh 60 150000 && unset PRECOMPUTE_PROXY_TOKEN && ");
        assertThat(command.indexOf("unset PRECOMPUTE_PROXY_TOKEN"))
                .isLessThan(command.indexOf(SandboxLayout.RUNNER_SCRIPT_FILENAME));
    }

    @Test
    void shouldCapPrecomputeAtNinetySecondsAndOneTenthOfTheJobDeadline() {
        assertThat(PracticePiAdapter.precomputeBudgetSeconds(3600)).isEqualTo(90);
        assertThat(PracticePiAdapter.precomputeBudgetSeconds(900)).isEqualTo(90);
        assertThat(PracticePiAdapter.precomputeBudgetSeconds(600)).isEqualTo(60);
        assertThat(PracticePiAdapter.precomputeBudgetSeconds(120)).isEqualTo(12);
        assertThat(PracticePiAdapter.precomputeBudgetSeconds(5)).isEqualTo(1);
    }

    @Test
    void shouldLeaveTheRunnerABudgetWhenTheTimeoutIsTheFloor() {
        PracticeAgentRequest request = new PracticeAgentRequest(
                "openai-completions",
                "m",
                null,
                null,
                null,
                "job-token-123",
                PracticePiAdapter.MIN_TIMEOUT_SECONDS,
                null,
                Map.of());

        assertThat(Long.parseLong(
                        adapter.buildSandboxSpec(request).environment().get("AGENT_BUDGET_MS")))
                .isPositive();
    }

    @ParameterizedTest
    @CsvSource({"600, 480000", "3600, 3450000", "120, 48000"})
    void shouldTakeThePrecomputeStageOutOfTheRunnerBudget(int timeoutSeconds, long expectedBudgetMs) {
        PracticeAgentRequest request = new PracticeAgentRequest(
                "openai-completions", "m", null, null, null, "job-token-123", timeoutSeconds, null, Map.of());

        long budgetMs =
                Long.parseLong(adapter.buildSandboxSpec(request).environment().get("AGENT_BUDGET_MS"));

        assertThat(budgetMs).isEqualTo(expectedBudgetMs);
    }

    @Test
    void shouldGiveThePrecomputeStageNoModelsAndNoTokenWhenTheJobHasNone() {
        var spec = adapter.buildSandboxSpec(proxyRequest());

        assertThat(spec.inputFiles()).doesNotContainKey(SandboxLayout.PRECOMPUTE_MODELS_FILE);
        assertThat(spec.environment()).doesNotContainKey("PRECOMPUTE_PROXY_TOKEN");
    }

    @Test
    void shouldWriteTheReviewModelAsTheOnlyChatSlotWhenNoPrecomputeModelIsBound() throws Exception {
        PracticeAgentRequest request = new PracticeAgentRequest(
                "openai-completions",
                "qwen-chat",
                null,
                null,
                null,
                "job-token-123",
                600,
                "precompute-token-456",
                Map.of(ModelKind.CHAT, model("openai-completions", "qwen-chat")));

        var spec = adapter.buildSandboxSpec(request);

        assertThat(spec.environment()).containsEntry("PRECOMPUTE_PROXY_TOKEN", "precompute-token-456");
        assertThat(spec.networkPolicy()).isNotNull();
        assertThat(spec.networkPolicy().llmProxyToken()).isEqualTo("job-token-123");
        JsonNode models = new ObjectMapper().readTree(spec.inputFiles().get(SandboxLayout.PRECOMPUTE_MODELS_FILE));
        assertThat(models.propertyNames()).containsExactly("chat");
    }

    @Test
    void shouldLeaveOutAReviewModelWhoseProtocolThePrecomputeRunnerCannotCall() throws Exception {
        FrozenModel legacyChat = model("azure-openai-responses", "gpt-5.4-mini");
        PracticeAgentRequest request = new PracticeAgentRequest(
                "azure-openai-responses",
                "gpt-5.4-mini",
                null,
                null,
                null,
                "job-token-123",
                600,
                "precompute-token-456",
                Map.of(ModelKind.CHAT, legacyChat, ModelKind.EMBEDDING, model("openai-embeddings", "embedder")));

        JsonNode models = new ObjectMapper()
                .readTree(adapter.buildSandboxSpec(request).inputFiles().get(SandboxLayout.PRECOMPUTE_MODELS_FILE));

        assertThat(models.propertyNames()).containsExactly("embedding");
        PracticeAgentRequest withoutSlots = new PracticeAgentRequest(
                "azure-openai-responses",
                "gpt-5.4-mini",
                null,
                null,
                null,
                "job-token-123",
                600,
                "precompute-token-456",
                Map.of(ModelKind.CHAT, legacyChat));
        assertThat(adapter.buildSandboxSpec(withoutSlots).inputFiles())
                .as("a job without any callable model has no models file")
                .doesNotContainKey(SandboxLayout.PRECOMPUTE_MODELS_FILE);
    }

    private static FrozenModel model(String protocol, String modelId) {
        return new FrozenModel(protocol, "https://models.example", modelId, null, null, null, null, null, null, null);
    }

    @Test
    void shouldKeepPracticeNetworkIsolatedBehindTheLlmProxy() {
        var policy = adapter.buildSandboxSpec(proxyRequest()).networkPolicy();
        assertThat(policy).isNotNull();
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
                "openai-completions",
                "gpt-oss-120b",
                131072,
                4096,
                ReasoningEffort.MEDIUM,
                "job-token-123",
                600,
                null,
                Map.of());

        var spec = adapter.buildSandboxSpec(request);

        assertThat(spec.image()).isEqualTo(IMAGE);
        var networkPolicy = spec.networkPolicy();
        assertThat(networkPolicy).isNotNull();
        assertThat(networkPolicy.llmProxyToken()).isEqualTo("job-token-123");
    }
}
