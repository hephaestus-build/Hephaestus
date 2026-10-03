package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * A streamed response reports its usage totals inside the SSE body rather than in a header, so a
 * streamed call is billed only if something reads the bytes on their way through.
 */
class ProxyStreamUsageTapTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ProxyStreamUsageTap completionsTap() {
        return new ProxyStreamUsageTap(MAPPER, false);
    }

    private static void feed(ProxyStreamUsageTap tap, String text) {
        tap.accept(text.getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    class ChatCompletions {

        /**
         * The shape a chat-completions stream actually has with {@code stream_options.include_usage}:
         * many delta frames carrying {@code "usage": null}, then one final frame carrying the totals.
         */
        @Test
        @DisplayName("reads the totals off the final frame, ignoring the null-usage deltas before it")
        void readsUsageFromTheFinalFrame() {
            ProxyStreamUsageTap tap = completionsTap();

            feed(tap, "data: {\"choices\":[{\"delta\":{\"content\":\"He\"}}],\"usage\":null}\n\n");
            feed(tap, "data: {\"choices\":[{\"delta\":{\"content\":\"llo\"}}],\"usage\":null}\n\n");
            feed(
                    tap,
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,"
                            + "\"prompt_tokens_details\":{\"cached_tokens\":20},"
                            + "\"completion_tokens_details\":{\"reasoning_tokens\":10}}}\n\n");
            feed(tap, "data: [DONE]\n\n");

            ProxyTokenUsage observed = tap.observed();
            assertThat(observed).isNotNull();
            // 100 prompt tokens of which 20 were cache reads: the input bucket is the remainder.
            assertThat(observed.billableInputTokens()).isEqualTo(80);
            assertThat(observed.cacheReadTokens()).isEqualTo(20);
            assertThat(observed.outputTokens()).isEqualTo(50);
            assertThat(observed.reasoningTokens()).isEqualTo(10);
            assertThat(observed.cacheWriteTokens()).isZero();
        }

        @Test
        @DisplayName("reassembles a usage frame split across chunk boundaries")
        void reassemblesAFrameSplitAcrossChunks() {
            ProxyStreamUsageTap tap = completionsTap();

            feed(tap, "data: {\"choices\":[],\"usa");
            feed(tap, "ge\":{\"prompt_tokens\":7,\"comple");
            feed(tap, "tion_tokens\":3}}\n");

            assertThat(tap.observed()).isNotNull();
            assertThat(tap.observed().billableInputTokens()).isEqualTo(7);
            assertThat(tap.observed().outputTokens()).isEqualTo(3);
        }

        /** The reported figure is cumulative for the whole response, so a repeat must not be billed twice. */
        @Test
        @DisplayName("a repeated usage block replaces the earlier one rather than adding to it")
        void laterUsageReplacesEarlier() {
            ProxyStreamUsageTap tap = completionsTap();

            feed(tap, "data: {\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}\n");
            feed(tap, "data: {\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4}}\n");

            var observed = tap.observed();
            assertThat(observed).isNotNull();
            assertThat(observed.billableInputTokens()).isEqualTo(9);
            assertThat(observed.outputTokens()).isEqualTo(4);
        }

        @Test
        @DisplayName("a stream cut off before the usage frame reports nothing rather than a guess")
        void aTruncatedStreamReportsNothing() {
            ProxyStreamUsageTap tap = completionsTap();

            feed(tap, "data: {\"choices\":[{\"delta\":{\"content\":\"partial answ\"}}],\"usage\":null}\n\n");

            assertThat(tap.observed()).isNull();
        }

        @Test
        void marksInvalidUsage() {
            ProxyStreamUsageTap tap = completionsTap();

            feed(tap, "data: {\"usage\":{\"prompt_tokens\":1,\"prompt_tokens_details\":{\"cached_tokens\":2}}}\n");

            assertThat(tap.observed()).isNull();
            assertThat(tap.hasMalformedUsage()).isTrue();
        }

        @Test
        @DisplayName("non-data lines are not parsed as frames")
        void ignoresNonDataLines() {
            ProxyStreamUsageTap tap = completionsTap();

            feed(tap, ": keep-alive with the word usage in it\n");
            feed(tap, "event: {\"usage\":{\"prompt_tokens\":999}}\n");

            assertThat(tap.observed()).isNull();
        }

        @Test
        @DisplayName("an unterminated frame is abandoned, and the next frame is still read")
        void abandonsAnOversizedFrameAndResynchronises() {
            ProxyStreamUsageTap tap = completionsTap();

            String filler = "x".repeat(64 * 1024);
            for (int i = 0; i < 20; i++) {
                feed(tap, filler);
            }
            feed(tap, "\ndata: {\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}\n");

            assertThat(tap.observed()).isNotNull();
            assertThat(tap.observed().billableInputTokens()).isEqualTo(5);
        }
    }

    @Nested
    class ResponsesProtocol {

        /**
         * The responses API nests usage under the terminal event's {@code response} envelope, which is
         * why the tap tries both placements.
         */
        @Test
        @DisplayName("reads usage from the response.completed envelope")
        void readsUsageFromTheCompletedEvent() {
            ProxyStreamUsageTap tap = new ProxyStreamUsageTap(MAPPER, true);

            feed(tap, "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}\n\n");
            feed(
                    tap,
                    "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":200,"
                            + "\"output_tokens\":70,\"input_tokens_details\":{\"cached_tokens\":50},"
                            + "\"output_tokens_details\":{\"reasoning_tokens\":25}}}}\n\n");

            ProxyTokenUsage observed = tap.observed();
            assertThat(observed).isNotNull();
            assertThat(observed.billableInputTokens()).isEqualTo(150);
            assertThat(observed.cacheReadTokens()).isEqualTo(50);
            assertThat(observed.outputTokens()).isEqualTo(70);
            assertThat(observed.reasoningTokens()).isEqualTo(25);
        }

        @Test
        @DisplayName("an incomplete response is billed for the tokens it did burn")
        void billsAnIncompleteResponse() {
            ProxyStreamUsageTap tap = new ProxyStreamUsageTap(MAPPER, true);

            feed(
                    tap,
                    "data: {\"type\":\"response.incomplete\",\"response\":{\"usage\":{\"input_tokens\":11,"
                            + "\"output_tokens\":4}}}\n\n");

            assertThat(tap.observed()).isNotNull();
            assertThat(tap.observed().billableInputTokens()).isEqualTo(11);
            assertThat(tap.observed().outputTokens()).isEqualTo(4);
        }
    }
}
