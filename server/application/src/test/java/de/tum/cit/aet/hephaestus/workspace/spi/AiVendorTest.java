package de.tum.cit.aet.hephaestus.workspace.spi;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class AiVendorTest extends BaseUnitTest {
    @Test
    void shouldRecogniseTheModelFamilyFromTheUpstreamId() {
        assertThat(AiVendor.ofModel("gpt-5")).isEqualTo(AiVendor.OPENAI);
        assertThat(AiVendor.ofModel("openai/gpt-oss-120b")).isEqualTo(AiVendor.OPENAI);
        assertThat(AiVendor.ofModel("o3-mini")).isEqualTo(AiVendor.OPENAI);
        assertThat(AiVendor.ofModel("claude-sonnet-5")).isEqualTo(AiVendor.ANTHROPIC);
        assertThat(AiVendor.ofModel("gemini-2.5-pro")).isEqualTo(AiVendor.GEMINI);
        assertThat(AiVendor.ofModel("google/gemma-3-27b-it")).isEqualTo(AiVendor.GEMMA);
        assertThat(AiVendor.ofModel("meta-llama/Llama-3.3-70B")).isEqualTo(AiVendor.META);
        assertThat(AiVendor.ofModel("mistral-large-latest")).isEqualTo(AiVendor.MISTRAL);
        assertThat(AiVendor.ofModel("Qwen/Qwen3-32B")).isEqualTo(AiVendor.QWEN);
        assertThat(AiVendor.ofModel("deepseek-r1")).isEqualTo(AiVendor.DEEPSEEK);
    }

    @Test
    void shouldLeaveAnUnknownModelWithoutAMark() {
        assertThat(AiVendor.ofModel("team-model-v2")).isNull();
        assertThat(AiVendor.ofModel("ollama")).isNull();
    }

    @Test
    void shouldRecogniseThePlatformFromTheHost() {
        assertThat(AiVendor.ofHost("https://acme.openai.azure.com/openai")).isEqualTo(AiVendor.AZURE);
        assertThat(AiVendor.ofHost("https://api.openai.com/v1")).isEqualTo(AiVendor.OPENAI);
        assertThat(AiVendor.ofHost("https://api.anthropic.com/v1")).isEqualTo(AiVendor.ANTHROPIC);
        assertThat(AiVendor.ofHost("http://gpu-01.internal:11434/v1")).isEqualTo(AiVendor.OLLAMA);
        assertThat(AiVendor.ofHost("http://ollama:8080/v1")).isEqualTo(AiVendor.OLLAMA);
    }

    @Test
    void shouldLeaveAnUnknownOrBrokenHostWithoutAMark() {
        assertThat(AiVendor.ofHost("https://llm.example.org/v1")).isNull();
        assertThat(AiVendor.ofHost("not a url")).isNull();
    }
}
