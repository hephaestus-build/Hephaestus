package de.tum.cit.aet.hephaestus.agent.practice;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
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
                new PracticeReviewProperties(
                        false, 15, 5, samplingTemperature, 12, 16000, 3, 150_000, 1000, Duration.ofMinutes(10)),
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
                "azure-openai-responses", "gpt-5.4-mini", null, null, null, "job-token-123", 600, Map.of());
    }

    @Test
    void shouldHandTheRunnerTheWorkEachPracticeIsOwed() {
        assertThat(adapter.buildSandboxSpec(proxyRequest()).environment())
                .containsEntry(PracticePiAdapter.PRACTICE_MODEL_CALLS_ENV, "12")
                .containsEntry(PracticePiAdapter.PRACTICE_OUTPUT_TOKENS_ENV, "16000")
                .containsEntry(PracticePiAdapter.PRACTICE_CONCURRENCY_ENV, "3");
    }

    @Test
    void buildsSpecWithImage() {
        var spec = adapter.buildSandboxSpec(proxyRequest());
        assertThat(spec.image()).isEqualTo(IMAGE);
    }

    @Test
    void shouldStageTheResultNormalizerAndNoPracticeScriptStageWhenBuildingTheSpec() {
        var spec = adapter.buildSandboxSpec(proxyRequest());

        assertThat(spec.inputFiles())
                .containsKeys("pi-change.ts", "pi-result-normalize.ts", "pi-task-paths.ts")
                .doesNotContainKeys("pi-practice-scripts.sh", "pi-practice-scripts.ts", "pi-observation-normalize.ts");
        assertThat(spec.command().getLast()).doesNotContain("pi-practice-scripts");
    }

    @Test
    void shouldLinkTheToolkitAfterTheWritableDirectoriesAndBeforeNodeWhenBuildingTheCommand() {
        String command = adapter.buildSandboxSpec(proxyRequest()).command().getLast();
        String link = "ln -sfn /opt/practice-scripts/lib /workspace/work/practice-scripts/lib";

        assertThat(command).contains("mkdir -p /workspace/work/practice-scripts/ && " + link + " && ");
        assertThat(command.indexOf(link)).isGreaterThan(command.indexOf("mkdir -p " + SandboxLayout.PI_AGENT_DIR));
        assertThat(command.indexOf(link)).isLessThan(command.indexOf("node "));
        assertThat(command.indexOf("node ")).isLessThan(command.indexOf(SandboxLayout.RUNNER_SCRIPT_FILENAME));
    }

    @Test
    void shouldGiveTheRunnerOneTokenAndTheDirectModelBudgetWhenBuildingTheSpec() {
        var spec = adapter.buildSandboxSpec(proxyRequest());

        assertThat(spec.environment())
                .containsEntry(PracticePiAdapter.DIRECT_MODEL_TOKENS_ENV, "150000")
                .doesNotContainKey("PRACTICE_SCRIPT_PROXY_TOKEN");
        assertThat(spec.command().getLast()).doesNotContain("PRACTICE_SCRIPT_PROXY_TOKEN");
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
                Map.of());

        assertThat(Long.parseLong(
                        adapter.buildSandboxSpec(request).environment().get("AGENT_BUDGET_MS")))
                .isPositive();
    }

    @ParameterizedTest
    @CsvSource({"600, 540000", "3600, 3540000", "120, 60000"})
    void shouldGiveTheRunnerTheWholeTimeoutLessTheShutdownBufferWhenBuildingTheSpec(
            int timeoutSeconds, long expectedBudgetMs) {
        PracticeAgentRequest request = new PracticeAgentRequest(
                "openai-completions", "m", null, null, null, "job-token-123", timeoutSeconds, Map.of());

        long budgetMs =
                Long.parseLong(adapter.buildSandboxSpec(request).environment().get("AGENT_BUDGET_MS"));

        assertThat(budgetMs).isEqualTo(expectedBudgetMs);
    }

    @Test
    void shouldStageNoModelsFileWhenTheJobHasNoDirectModels() {
        var spec = adapter.buildSandboxSpec(proxyRequest());

        assertThat(spec.inputFiles()).doesNotContainKey(SandboxLayout.PRACTICE_SCRIPT_MODELS_FILE);
    }

    @Test
    void shouldWriteTheReviewModelAsTheOnlyChatSlotWhenNoPracticeScriptModelIsBound() throws Exception {
        PracticeAgentRequest request = new PracticeAgentRequest(
                "openai-completions",
                "qwen-chat",
                null,
                null,
                null,
                "job-token-123",
                600,
                Map.of(ModelKind.CHAT, model("openai-completions", "qwen-chat")));

        var spec = adapter.buildSandboxSpec(request);

        assertThat(spec.environment()).doesNotContainKey("PRACTICE_SCRIPT_PROXY_TOKEN");
        assertThat(spec.gatewayToken()).isEqualTo("job-token-123");
        JsonNode models = new ObjectMapper().readTree(spec.inputFiles().get(SandboxLayout.PRACTICE_SCRIPT_MODELS_FILE));
        assertThat(models.propertyNames()).containsExactly("chat");
    }

    private static FrozenModel model(String protocol, String modelId) {
        return new FrozenModel(
                LlmApiProtocol.parse(protocol).orElseThrow(),
                "https://models.example",
                modelId,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void shouldGiveTheSandboxTheJobTokenAsItsGatewayCredential() {
        assertThat(adapter.buildSandboxSpec(proxyRequest()).gatewayToken()).isEqualTo("job-token-123");
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
                Map.of());

        var spec = adapter.buildSandboxSpec(request);

        assertThat(spec.image()).isEqualTo(IMAGE);
        assertThat(spec.gatewayToken()).isEqualTo("job-token-123");
    }
}
