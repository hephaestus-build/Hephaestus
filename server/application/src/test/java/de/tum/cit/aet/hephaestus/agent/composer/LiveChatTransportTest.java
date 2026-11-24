package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmCredentials;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Each case of a live eval reports only the models that served its own calls and every request it sent, and reads the
 * composer's streamed answers as the model session does.
 */
@Tag("unit")
class LiveChatTransportTest {
    private static final JsonMapper MAPPER = new JsonMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer endpoint;

    @BeforeEach
    void startTheEndpoint() throws IOException {
        endpoint = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        endpoint.createContext("/v1/chat/completions", exchange -> {
            byte[] body = ("{\"model\":\"served-" + calls.incrementAndGet() + "\",\"choices\":[]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        endpoint.start();
    }

    @AfterEach
    void stopTheEndpoint() {
        endpoint.stop(0);
    }

    @Test
    void shouldReportOnlyTheModelsServedSinceTheLastResetWhenACaseStartsANewMeasurement() {
        var transport = new LiveChatTransport(new LiveLlmCredentials(
                "http://127.0.0.1:" + endpoint.getAddress().getPort(), "test-key", "requested"));

        transport.call("{}".getBytes(StandardCharsets.UTF_8));
        assertThat(transport.servedModels()).containsExactly("served-1");

        transport.startCase();
        transport.call("{}".getBytes(StandardCharsets.UTF_8));

        assertThat(transport.servedModels()).containsExactly("served-2");
    }

    @Test
    void shouldCountEveryRequestTheEndpointReceivesAndSendNoSecondRequestAfterANotFound() throws IOException {
        var received = new AtomicInteger();
        var missing = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        missing.createContext("/", exchange -> {
            received.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        missing.start();
        try {
            var transport = new LiveChatTransport(new LiveLlmCredentials(
                    "http://127.0.0.1:" + missing.getAddress().getPort(), "test-key", "requested"));

            var answer = transport.call("{}".getBytes(StandardCharsets.UTF_8));

            assertThat(answer.status()).isEqualTo(404);
            assertThat(received.get()).isEqualTo(1);
            assertThat(transport.requests()).isEqualTo(received.get());
        } finally {
            missing.stop(0);
        }
    }

    /**
     * A composer's streamed answer as the live endpoint sends it: a scheduling comment, reasoning, one tool call whose
     * arguments arrive in pieces, a usage chunk and the end marker. The composer streams every call, so every live eval
     * receives this shape.
     */
    private static final String STREAMED_ANSWER = """
            : logos-schedule x-logos-ettft-ms=1537 x-logos-ettft-tier=warm

            data: {"id":"c1","object":"chat.completion.chunk","model":"served-stream","choices":[{"index":0,"delta":{"role":"assistant"}}]}

            data: {"id":"c1","object":"chat.completion.chunk","model":"served-stream","choices":[{"index":0,"delta":{"reasoning_content":"The view fetches its data itself."}}]}

            data: {"id":"c1","object":"chat.completion.chunk","model":"served-stream","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"check_draft","arguments":"{\\"draft\\":"}}]}}]}

            data: {"id":"c1","object":"chat.completion.chunk","model":"served-stream","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"a\\"}"}}]}}]}

            data: {"id":"c1","object":"chat.completion.chunk","model":"served-stream","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

            data: {"id":"c1","object":"chat.completion.chunk","model":"served-stream","choices":[],"usage":{"prompt_tokens":9,"completion_tokens":7}}

            data: [DONE]

            """;

    private static final String FINISH_EVENT_START =
            "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"served-stream\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\"";

    @Test
    void shouldAssembleAStreamedAnswerIntoTheBodyThatTheComposerReadsWhenTheEndpointStreams() throws IOException {
        var streaming = streamingEndpoint(STREAMED_ANSWER);
        try {
            var transport = new LiveChatTransport(new LiveLlmCredentials(
                    "http://127.0.0.1:" + streaming.getAddress().getPort(), "test-key", "requested"));

            var answer = transport.call("{\"stream\":true}".getBytes(StandardCharsets.UTF_8));

            assertThat(answer.status()).isEqualTo(200);
            var choice = MAPPER.readTree(answer.body()).path("choices").path(0);
            assertThat(choice.path("finish_reason").asString("")).isEqualTo("tool_calls");
            var call = choice.path("message").path("tool_calls").path(0);
            assertThat(call.path("id").asString("")).isEqualTo("call-1");
            assertThat(call.path("function").path("name").asString("")).isEqualTo("check_draft");
            assertThat(call.path("function").path("arguments").asString("")).isEqualTo("{\"draft\":\"a\"}");
            assertThat(transport.servedModels()).containsExactly("served-stream");
        } finally {
            streaming.stop(0);
        }
    }

    @Test
    void shouldReportAnEndpointFailureWhenTheStreamIsCutBeforeItsEnd() throws IOException {
        var streaming = streamingEndpoint(STREAMED_ANSWER.substring(0, STREAMED_ANSWER.indexOf(FINISH_EVENT_START)));
        try {
            var transport = new LiveChatTransport(new LiveLlmCredentials(
                    "http://127.0.0.1:" + streaming.getAddress().getPort(), "test-key", "requested"));

            var answer = transport.call("{\"stream\":true}".getBytes(StandardCharsets.UTF_8));

            assertThat(answer.status()).isEqualTo(503);
            assertThat(answer.body()).isEmpty();
        } finally {
            streaming.stop(0);
        }
    }

    /** An endpoint that answers every chat call with {@code stream} as server-sent events. */
    private static HttpServer streamingEndpoint(String stream) throws IOException {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = stream.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "text/event-stream;charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    @Test
    void shouldCountAsManyRequestsAsTheEndpointServedWhenEachCallSucceeds() {
        var transport = new LiveChatTransport(new LiveLlmCredentials(
                "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/v1/", "test-key", "requested"));

        for (int call = 0; call < 3; call++) transport.call("{}".getBytes(StandardCharsets.UTF_8));

        assertThat(calls.get()).isEqualTo(3);
        assertThat(transport.requests()).isEqualTo(calls.get());
    }
}
