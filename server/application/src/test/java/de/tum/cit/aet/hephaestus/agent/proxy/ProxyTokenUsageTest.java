package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class ProxyTokenUsageTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"cache_write_tokens", "created_cache_tokens"})
    void readsSupportedChatCacheWriteFields(String field) throws Exception {
        var body = MAPPER.readTree("""
            {"usage":{"prompt_tokens":100,"completion_tokens":2,
            "prompt_tokens_details":{"cached_tokens":20,"%s":30}}}
            """.formatted(field));

        assertThat(ProxyTokenUsage.from(body, false)).isEqualTo(new ProxyTokenUsage(50, 2, 0, 20, 30));
    }

    /** Two identical gpt-oss requests to a vLLM endpoint with prefix caching: the first fills the cache. */
    @Test
    void shouldCountNativeAnthropicCacheBucketsApartFromUncachedInput() {
        var body = MAPPER.readTree(
                "{\"usage\":{\"input_tokens\":13,\"output_tokens\":7,\"cache_read_input_tokens\":1200,\"cache_creation_input_tokens\":300}}");
        var usage = ProxyTokenUsage.from(body, LlmApiProtocol.ANTHROPIC_MESSAGES);
        assertThat(usage).isNotNull().isEqualTo(new ProxyTokenUsage(13, 7, 0, 1200, 300));
        assertThat(usage.promptTokens()).isEqualTo(1513);
    }

    @Test
    void keepsOnlyTheUncachedPromptAsInputWhenAPrefixCacheHoldsTheRest() throws Exception {
        var first = MAPPER.readTree("""
                {"usage":{"prompt_tokens":1277,"completion_tokens":40,
                "prompt_tokens_details":{"cached_tokens":0,"created_cache_tokens":1264}}}
                """);
        var second = MAPPER.readTree("""
                {"usage":{"prompt_tokens":1277,"completion_tokens":40,
                "prompt_tokens_details":{"cached_tokens":1264,"created_cache_tokens":0}}}
                """);

        assertThat(ProxyTokenUsage.from(first, false)).isEqualTo(new ProxyTokenUsage(13, 40, 0, 0, 1264));
        assertThat(ProxyTokenUsage.from(second, false)).isEqualTo(new ProxyTokenUsage(13, 40, 0, 1264, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prompt_cache_hit_tokens", "cached_tokens"})
    void readsSupportedChatCacheReadFields(String field) throws Exception {
        var body = MAPPER.readTree("""
            {"usage":{"prompt_tokens":100,"completion_tokens":2,"%s":20,
            "prompt_tokens_details":{"created_cache_tokens":30}}}
            """.formatted(field));

        assertThat(ProxyTokenUsage.from(body, false)).isEqualTo(new ProxyTokenUsage(50, 2, 0, 20, 30));
    }

    @Test
    void nestedChatReadCountTakesPrecedenceOverFallbacks() throws Exception {
        var body = MAPPER.readTree("""
            {"usage":{"prompt_tokens":100,"completion_tokens":2,
            "prompt_cache_hit_tokens":40,"cached_tokens":50,
            "prompt_tokens_details":{"cached_tokens":20,"created_cache_tokens":30}}}
            """);

        assertThat(ProxyTokenUsage.from(body, false)).isEqualTo(new ProxyTokenUsage(50, 2, 0, 20, 30));
    }

    @Test
    void cacheHitCountTakesPrecedenceOverTopLevelReadCount() throws Exception {
        var body = MAPPER.readTree("""
            {"usage":{"prompt_tokens":100,"completion_tokens":2,
            "prompt_cache_hit_tokens":20,"cached_tokens":50,
            "prompt_tokens_details":{"created_cache_tokens":30}}}
            """);

        assertThat(ProxyTokenUsage.from(body, false)).isEqualTo(new ProxyTokenUsage(50, 2, 0, 20, 30));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1.5", "2147483648", "\"20\""})
    void rejectsInvalidChatCacheReadFallback(String value) throws Exception {
        var body = MAPPER.readTree("""
            {"usage":{"prompt_tokens":100,"completion_tokens":2,
            "prompt_cache_hit_tokens":%s,"cached_tokens":20}}
            """.formatted(value));

        assertThatIllegalArgumentException().isThrownBy(() -> ProxyTokenUsage.from(body, false));
    }

    @Test
    void responsesDoNotReadCompletionsOnlyCacheFields() throws Exception {
        var body = MAPPER.readTree("""
            {"usage":{"input_tokens":100,"output_tokens":2,
            "prompt_cache_hit_tokens":20,"cached_tokens":50}}
            """);

        assertThat(ProxyTokenUsage.from(body, true)).isEqualTo(new ProxyTokenUsage(100, 2, 0, 0, 0));
    }

    @Test
    void readsResponsesCacheWrites() throws Exception {
        var body = MAPPER.readTree("""
            {"usage":{"input_tokens":100,"output_tokens":2,
            "input_tokens_details":{"cached_tokens":20,"cache_write_tokens":30}}}
            """);

        assertThat(ProxyTokenUsage.from(body, true)).isEqualTo(new ProxyTokenUsage(50, 2, 0, 20, 30));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"prompt_tokens\":-1}",
                "{\"prompt_tokens\":1.5}",
                "{\"prompt_tokens\":2147483648}",
                "{\"prompt_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":11}}",
                "{\"prompt_tokens\":10,\"prompt_tokens_details\":{\"cache_write_tokens\":2,\"created_cache_tokens\":3}}"
            })
    void rejectsInvalidUsage(String usage) throws Exception {
        var body = MAPPER.readTree("{\"usage\":" + usage + "}");

        assertThatIllegalArgumentException().isThrownBy(() -> ProxyTokenUsage.from(body, false));
    }

    @Test
    void rejectsIncompleteUsage() throws Exception {
        var chat = MAPPER.readTree("{\"usage\":{\"prompt_tokens\":10}}");
        var responses = MAPPER.readTree("{\"usage\":{\"output_tokens\":2}}");

        assertThatIllegalArgumentException().isThrownBy(() -> ProxyTokenUsage.from(chat, false));
        assertThatIllegalArgumentException().isThrownBy(() -> ProxyTokenUsage.from(responses, true));
    }

    @Test
    void missingUsageReturnsNull() throws Exception {
        assertThat(ProxyTokenUsage.from(MAPPER.readTree("{}"), false)).isNull();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "openai-completions|{\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":1}}|9|1",
                "openai-responses|{\"usage\":{\"input_tokens\":7,\"output_tokens\":2}}|7|2",
                "anthropic-messages|{\"usage\":{\"input_tokens\":5,\"output_tokens\":4}}|5|4"
            })
    void shouldReadTheUsageShapeOfEachProtocolWhenACallIsServed(String protocol, String body, int input, int output)
            throws Exception {
        var usage = ProxyTokenUsage.from(
                MAPPER.readTree(body), LlmApiProtocol.parse(protocol).orElseThrow());

        assertThat(usage).isEqualTo(new ProxyTokenUsage(input, output, 0, 0, 0));
    }
}
