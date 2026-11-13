package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.ProviderModelLimits.Limits;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Each metadata shape that Hephaestus reads limits from. The vLLM listing is recorded from a vLLM-based gateway; the
 * others are the providers' documented examples.
 */
class ProviderModelLimitsTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static JsonNode fixture(String name) {
        try (InputStream in = Objects.requireNonNull(
                ProviderModelLimitsTest.class.getResourceAsStream("/llm-model-metadata/" + name))) {
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void shouldReadTheContextWindowThatAVllmServerRunsWith() {
        var limits = ProviderModelLimits.fromModelList(LlmApiProtocol.OPENAI_COMPLETIONS, fixture("vllm-models.json"));

        assertThat(limits).containsEntry("openai/gpt-oss-120b", new Limits(131_072, null));
        // Only max_model_len is the window that the server runs with.
        assertThat(limits).doesNotContainKey("autotrust/JEV-27B-VL");
    }

    @Test
    void shouldReadTheSmallerOpenRouterWindowAndTheTopProvidersMaximumOutput() {
        var limits =
                ProviderModelLimits.fromModelList(LlmApiProtocol.OPENAI_COMPLETIONS, fixture("openrouter-models.json"));

        assertThat(limits).containsEntry("deepseek/deepseek-v3.2", new Limits(131_072, 65_536));
        assertThat(limits).containsEntry("qwen/qwen3-coder", new Limits(262_144, null));
    }

    @Test
    void shouldReadAnthropicsInputWindowAndMaxTokens() {
        var limits =
                ProviderModelLimits.fromModelList(LlmApiProtocol.ANTHROPIC_MESSAGES, fixture("anthropic-models.json"));

        assertThat(limits).containsEntry("claude-opus-5-5", new Limits(1_000_000, 128_000));
        assertThat(limits).doesNotContainKey("claude-legacy");
    }

    @Test
    void shouldReadAnthropicFieldsOnlyFromAnAnthropicListing() {
        var limits =
                ProviderModelLimits.fromModelList(LlmApiProtocol.OPENAI_COMPLETIONS, fixture("anthropic-models.json"));

        assertThat(limits).isEmpty();
    }

    @Test
    void shouldTakeTheSmallestLimitsOfALiteLlmModelGroup() {
        var limits = ProviderModelLimits.fromLiteLlmModelInfo(fixture("litellm-model-info.json"));

        assertThat(limits).containsEntry("team-gpt", new Limits(128_000, 16_384));
        assertThat(limits).doesNotContainKey("self-hosted");
    }

    @Test
    void shouldReadTheWindowThatAnOllamaModelRunsWith() throws Exception {
        assertThat(ProviderModelLimits.fromOllamaShow(fixture("ollama-show.json")))
                .isEqualTo(new Limits(32_768, null));
        // A num_ctx above the trained length cannot hold more than the model was trained on.
        var larger = MAPPER.readTree("{\"parameters\":\"num_ctx 262144\",\"model_info\":{\"general.architecture\":"
                + "\"llama\",\"llama.context_length\":131072}}");
        assertThat(ProviderModelLimits.fromOllamaShow(larger)).isEqualTo(new Limits(131_072, null));
    }

    /** Without num_ctx, Ollama runs the server's default window, which the trained length overstates. */
    @Test
    void shouldReadNoWindowFromAnOllamaModelWithoutNumCtx() throws Exception {
        var show = MAPPER.readTree("{\"parameters\":\"num_keep 24\",\"model_info\":{\"general.architecture\":"
                + "\"llama\",\"llama.context_length\":131072}}");
        assertThat(ProviderModelLimits.fromOllamaShow(show)).isEqualTo(Limits.NONE);
    }

    @Test
    void shouldFindNothingInAnOpenAiListingOrAnUnexpectedBody() throws Exception {
        var openAi = MAPPER.readTree(
                "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-6.1-sol\",\"object\":\"model\",\"created\":1,\"owned_by\":\"openai\"}]}");
        assertThat(ProviderModelLimits.fromModelList(LlmApiProtocol.OPENAI_RESPONSES, openAi))
                .isEmpty();
        var negative = MAPPER.readTree("{\"data\":[{\"id\":\"m\",\"max_model_len\":-1,\"context_length\":\"8k\"}]}");
        assertThat(ProviderModelLimits.fromModelList(LlmApiProtocol.OPENAI_COMPLETIONS, negative))
                .isEmpty();
        assertThat(ProviderModelLimits.fromLiteLlmModelInfo(MAPPER.readTree("[]")))
                .isEmpty();
        assertThat(ProviderModelLimits.fromOllamaShow(MAPPER.readTree("{\"model_info\":{}}")))
                .isEqualTo(Limits.NONE);
    }
}
