package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionHealth;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class LlmProxyServiceTest extends BaseUnitTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private LlmModelResolver resolver;

    @Mock
    private ProxyRequestPolicy requestPolicy;

    @Mock
    private ProxyBudgetGate budgetGate;

    @Mock
    private ProxyUsageAccumulator usageAccumulator;

    @Mock
    private MentorTurnUsageAccumulator mentorTurnUsageAccumulator;

    private LlmProxyService controller;

    @BeforeEach
    void setUp() {
        lenient().when(requestPolicy.allows(any())).thenReturn(true);
        controller = new LlmProxyService(
                Tracer.NOOP,
                WebClient.create(),
                resolver,
                OBJECT_MAPPER,
                new ProxyAccounting(
                        budgetGate,
                        usageAccumulator,
                        mentorTurnUsageAccumulator,
                        new SimpleMeterRegistry(),
                        OBJECT_MAPPER),
                requestPolicy,
                new ProxyCallsInFlight(),
                new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults()),
                HEPH_FIRST_BYTE,
                HEPH_IDLE);
    }

    @Test
    void shouldRefuseNoAiBeforeCredentialsOrNetworkAccess() {
        var routing = routing("openai-completions");
        authenticate(routing);
        when(requestPolicy.allows(routing)).thenReturn(false);
        var result = controller.proxy(
                request("POST", "/internal/llm/chat/completions"),
                new MockHttpServletResponse(),
                new HttpHeaders(),
                jsonBody());
        assertThat(result).isNotNull();
        assertThat(result.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(resolver, budgetGate);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    class BudgetGate {

        @Test
        void the429BodyNamesTheSharedPurseForASharedModelCall() {
            var routing = routing("openai-completions");
            authenticate(routing);
            when(budgetGate.isBlocked(routing)).thenReturn(true);

            var result = controller.proxy(
                    request("POST", "/internal/llm/chat/completions"),
                    new MockHttpServletResponse(),
                    new HttpHeaders(),
                    jsonBody());

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode().value()).isEqualTo(429);
            assertThat(String.valueOf(result.getBody()))
                    .contains("Shared-model budget reached")
                    .contains("raises the budget");
        }

        @Test
        void the429BodyNamesTheOwnProviderPurseForAnOwnProviderCall() {
            var routing = workspaceFundedRouting();
            authenticate(routing);
            when(budgetGate.isBlocked(routing)).thenReturn(true);

            var result = controller.proxy(
                    request("POST", "/internal/llm/chat/completions"),
                    new MockHttpServletResponse(),
                    new HttpHeaders(),
                    jsonBody());

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode().value()).isEqualTo(429);
            assertThat(String.valueOf(result.getBody()))
                    .contains("Own-provider budget reached")
                    .contains("raises the cap");
        }

        /** 502, not 429: the credential resolves to nothing, so anything but 429 proves the call got through. */
        @Test
        void doesNotRejectACallTheGateAllows() {
            var routing = workspaceFundedRouting();
            authenticate(routing);
            when(budgetGate.isBlocked(routing)).thenReturn(false);
            when(resolver.resolveProxyCredential(any())).thenReturn(null);

            var result = controller.proxy(
                    request("POST", "/internal/llm/chat/completions"),
                    new MockHttpServletResponse(),
                    new HttpHeaders(),
                    jsonBody());

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode().value()).isEqualTo(502);
        }
    }

    @Nested
    class SafeSurface {

        /** A truncated runner or a retried request whose body was already consumed sends no bytes. */
        @Test
        void shouldRejectABodylessCallBeforeCredentialResolution() {
            authenticate(routing("openai-responses"));

            var result = controller.proxy(
                    request("POST", "/internal/llm/responses"), new MockHttpServletResponse(), new HttpHeaders(), null);

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode().value()).isEqualTo(400);
            verifyNoInteractions(resolver);
        }

        @Test
        void shouldRejectNonPostBeforeCredentialResolution() {
            authenticate(routing("openai-completions"));
            var request = request("GET", "/internal/llm/chat/completions");

            var result = controller.proxy(request, new MockHttpServletResponse(), new HttpHeaders(), jsonBody());

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode().value()).isEqualTo(405);
            verifyNoInteractions(resolver);
        }

        @Test
        void shouldRejectWrongPathBeforeCredentialResolution() {
            authenticate(routing("openai-completions"));
            var request = request("POST", "/internal/llm/models");

            var result = controller.proxy(request, new MockHttpServletResponse(), new HttpHeaders(), jsonBody());

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode().value()).isEqualTo(404);
            verifyNoInteractions(resolver);
        }

        @Test
        void shouldRejectQueryBeforeCredentialResolution() {
            authenticate(routing("openai-responses"));
            var request = request("POST", "/internal/llm/responses");
            request.setQueryString("api-version=unsafe");

            var result = controller.proxy(request, new MockHttpServletResponse(), new HttpHeaders(), jsonBody());

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode().value()).isEqualTo(400);
            verifyNoInteractions(resolver);
        }

        @Test
        void shouldRejectProtocolPathMismatchBeforeCredentialResolution() {
            authenticate(routing("openai-responses"));
            var request = request("POST", "/internal/llm/chat/completions");

            var result = controller.proxy(request, new MockHttpServletResponse(), new HttpHeaders(), jsonBody());

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode().value()).isEqualTo(404);
            verifyNoInteractions(resolver);
        }

        @Test
        void shouldAcceptChatCompletionsPath() {
            var routing = routing("openai-completions");
            authenticate(routing);
            stubCredential(routing, credential("openai-completions", LlmAuthMode.BEARER));
            doThrow(new IllegalArgumentException("blocked"))
                    .when(requestPolicy)
                    .validateTarget("https://api.example.com/v1");

            var result = controller.proxy(
                    request("POST", "/internal/llm/chat/completions"),
                    new MockHttpServletResponse(),
                    new HttpHeaders(),
                    jsonBody());

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode().value()).isEqualTo(502);
        }

        @Test
        void shouldAcceptResponsesPath() {
            var routing = routing("openai-responses");
            authenticate(routing);
            stubCredential(routing, credential("openai-responses", LlmAuthMode.API_KEY));
            doThrow(new IllegalArgumentException("blocked"))
                    .when(requestPolicy)
                    .validateTarget("https://api.example.com/v1");

            var result = controller.proxy(
                    request("POST", "/internal/llm/responses"),
                    new MockHttpServletResponse(),
                    new HttpHeaders(),
                    jsonBody());

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode().value()).isEqualTo(502);
        }

        @Test
        void shouldRejectWhenLiveConnectionProtocolDiffersFromFrozenRouting() {
            var routing = routing("openai-completions");
            authenticate(routing);
            stubCredential(routing, credential("openai-responses", LlmAuthMode.BEARER));

            var result = controller.proxy(
                    request("POST", "/internal/llm/chat/completions"),
                    new MockHttpServletResponse(),
                    new HttpHeaders(),
                    jsonBody());

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode().value()).isEqualTo(502);
            verify(requestPolicy, never()).validateTarget(any());
        }
    }

    @Nested
    class BodyLocking {

        @Test
        void shouldForceAuthoritativeModel() {
            byte[] input = "{\"model\":\"runner-controlled\",\"service_tier\":\"priority\",\"messages\":[]}"
                    .getBytes(StandardCharsets.UTF_8);

            var prepared = controller.prepareBody(input, "catalog-model", false);
            assertThat(prepared).isNotNull();

            assertThat(prepared.body()).isNotNull();
            var tree = OBJECT_MAPPER.readTree(prepared.body());
            assertThat(tree.path("model").asString()).isEqualTo("catalog-model");
            assertThat(tree.has("service_tier")).isFalse();
        }

        @Test
        void shouldRejectMalformedJson() {
            assertThat(controller.prepareBody("not-json".getBytes(StandardCharsets.UTF_8), "model", false))
                    .isNull();
        }

        @Test
        void shouldRejectJsonThatIsNotAnObject() {
            assertThat(controller.prepareBody("[]".getBytes(StandardCharsets.UTF_8), "model", false))
                    .isNull();
        }

        @Test
        void shouldAddStreamingUsageForChatCompletions() {
            byte[] input = "{\"stream\":true,\"messages\":[]}".getBytes(StandardCharsets.UTF_8);

            var prepared = controller.prepareBody(input, "catalog-model", true);
            assertThat(prepared).isNotNull();

            assertThat(prepared.body()).isNotNull();
            var tree = OBJECT_MAPPER.readTree(prepared.body());
            assertThat(tree.path("model").asString()).isEqualTo("catalog-model");
            assertThat(tree.path("stream_options").path("include_usage").asBoolean())
                    .isTrue();
            // The un-augmented body is kept so a provider that rejects the flag can be retried on
            // exactly what the caller sent.
            assertThat(OBJECT_MAPPER.readTree(prepared.withoutUsageRequest()).has("stream_options"))
                    .isFalse();
        }

        static Stream<Arguments> requestedCapabilities() {
            return Stream.of(
                    Arguments.of(
                            "{\"tools\":[{\"type\":\"function\"},{\"type\":\"custom\"}]}", true, "caller-run tools"),
                    Arguments.of("{\"tools\":[{\"type\":\"web_search_preview\"}]}", false, "a provider-hosted tool"),
                    Arguments.of("{\"web_search_options\":{}}", false, "hosted search asked for outside tools"),
                    Arguments.of("{\"modalities\":[\"text\",\"audio\"],\"audio\":{}}", false, "audio output"),
                    Arguments.of("{\"modalities\":[\"text\"]}", true, "an explicit text-only modality"));
        }

        @ParameterizedTest(name = "{2}")
        @MethodSource("requestedCapabilities")
        void locksTheRequestedCapabilitySurface(String body, boolean allowed, String what) {
            var prepared = controller.prepareBody(body.getBytes(StandardCharsets.UTF_8), "model", false);

            if (allowed) {
                assertThat(prepared).as(what).isNotNull();
            } else {
                assertThat(prepared).as(what).isNull();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "COMPLETED, 200, OK",
        "STREAM_FAILED, 200, ERROR",
        "CLIENT_DISCONNECTED, 200, ERROR",
        "HTTP_ERROR, 502, ERROR"
    })
    void shouldExportTheOperationOutcomeWithoutLeakingFailureDetails(
            String outcome, int status, StatusCode expectedStatus) throws Exception {
        var exporter = mock(SpanExporter.class);
        when(exporter.export(any())).thenReturn(CompletableResultCode.ofSuccess());
        when(exporter.shutdown()).thenReturn(CompletableResultCode.ofSuccess());
        try (var provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            var tracer = new OtelTracer(provider.get("test"), new OtelCurrentTraceContext(), event -> {});
            var buffer =
                    DefaultDataBufferFactory.sharedInstance.wrap("data: response\n\n".getBytes(StandardCharsets.UTF_8));
            Flux<DataBuffer> body = outcome.equals("STREAM_FAILED")
                    ? Flux.error(new IOException("private-provider-token"))
                    : Flux.just(buffer);
            var upstreamResponse = ClientResponse.create(HttpStatusCode.valueOf(status))
                    .header(HttpHeaders.CONTENT_TYPE, status == 200 ? "text/event-stream" : "application/json")
                    .body(body)
                    .build();
            var client = WebClient.builder()
                    .exchangeFunction(request -> Mono.just(upstreamResponse))
                    .build();
            var service = new LlmProxyService(
                    tracer,
                    client,
                    resolver,
                    OBJECT_MAPPER,
                    new ProxyAccounting(
                            budgetGate,
                            usageAccumulator,
                            mentorTurnUsageAccumulator,
                            new SimpleMeterRegistry(),
                            OBJECT_MAPPER),
                    requestPolicy,
                    new ProxyCallsInFlight(),
                    new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults()),
                    HEPH_FIRST_BYTE,
                    HEPH_IDLE);
            var routing = routing("openai-completions");
            authenticate(routing);
            when(resolver.resolveProxyCredential(any()))
                    .thenReturn(credential("openai-completions", LlmAuthMode.BEARER));
            var servletResponse = new MockHttpServletResponse();
            HttpServletResponse response = servletResponse;
            boolean clientDisconnected = outcome.equals("CLIENT_DISCONNECTED");
            if (clientDisconnected) {
                var disconnected = mock(HttpServletResponse.class);
                var output = mock(ServletOutputStream.class);
                when(disconnected.getOutputStream()).thenReturn(output);
                when(disconnected.getStatus()).thenReturn(200);
                doThrow(new IOException("private-client-detail")).when(output).write(any(byte[].class));
                response = disconnected;
            }
            var result = service.proxy(
                    request("POST", "/internal/llm/chat/completions"),
                    response,
                    new HttpHeaders(),
                    "{\"stream\":true,\"messages\":[]}".getBytes(StandardCharsets.UTF_8));
            if (status == 200) {
                assertThat(result).isNull();
                if (!clientDisconnected) {
                    assertThat(servletResponse.getStatus()).isEqualTo(200);
                }
            } else {
                assertThat(result).isNotNull();
                assertThat(result.getStatusCode().value()).isEqualTo(status);
            }
            verify(exporter).export(argThat(spans -> {
                assertThat(spans).hasSize(1);
                var span = spans.iterator().next();
                assertThat(span.getStatus().getStatusCode()).isEqualTo(expectedStatus);
                var errorType = span.getAttributes().get(AttributeKey.stringKey("error.type"));
                if (expectedStatus == StatusCode.ERROR) {
                    assertThat(errorType).isEqualTo(status == 502 ? "502" : outcome);
                } else {
                    assertThat(errorType).isNull();
                }
                assertThat(span.getEvents().toString())
                        .doesNotContain("private-provider-token", "private-client-detail");
                return true;
            }));
        }
    }

    /**
     * These tests drive an actual HTTP stream because the failure they guard against is invisible to any
     * mock: a controller that returns the instant it sees an SSE body serves a perfect response and
     * reaches no accounting at all, so only the ledger tells the two apart.
     */
    @Nested
    class Streaming {

        private MockWebServer upstream;
        private LlmProxyService streamingController;
        private SimpleMeterRegistry streamingMeterRegistry;
        private final ProxyCallsInFlight streamingCalls = new ProxyCallsInFlight();

        @BeforeEach
        void startUpstream() throws IOException {
            upstream = new MockWebServer();
            upstream.start();
            streamingMeterRegistry = new SimpleMeterRegistry();
            streamingController = new LlmProxyService(
                    Tracer.NOOP,
                    WebClient.builder().build(),
                    resolver,
                    OBJECT_MAPPER,
                    new ProxyAccounting(
                            budgetGate,
                            usageAccumulator,
                            mentorTurnUsageAccumulator,
                            streamingMeterRegistry,
                            OBJECT_MAPPER),
                    requestPolicy,
                    streamingCalls,
                    new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults()),
                    HEPH_FIRST_BYTE,
                    HEPH_IDLE);
        }

        @AfterEach
        void stopUpstream() throws IOException {
            upstream.close();
        }

        private ProxyRouting streamingRouting() {
            return new ProxyRouting(
                    "job:stream",
                    "openai-completions",
                    upstream.url("/v1").toString(),
                    FundingSource.INSTANCE,
                    7L,
                    8L,
                    9L,
                    ATTEMPT);
        }

        private MockHttpServletResponse proxyStream(String... sseFrames) {
            StringBuilder body = new StringBuilder();
            for (String frame : sseFrames) {
                body.append("data: ").append(frame).append("\n\n");
            }
            upstream.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body(body.toString())
                    .build());
            ProxyRouting routing = streamingRouting();
            authenticate(routing);
            when(resolver.resolveProxyCredential(new LlmModelResolver.ConnectionRef(
                            routing.connectionScope(),
                            routing.connectionId(),
                            routing.modelId(),
                            routing.workspaceId())))
                    .thenReturn(new LlmModelResolver.ProxyCredential(
                            upstream.url("/v1").toString(),
                            "openai-completions",
                            LlmAuthMode.BEARER,
                            "catalog-model",
                            "secret"));
            MockHttpServletResponse response = new MockHttpServletResponse();
            controllerProxy(response);
            return response;
        }

        private void controllerProxy(MockHttpServletResponse response) {
            streamingController.proxy(
                    request("POST", "/internal/llm/chat/completions"),
                    response,
                    new HttpHeaders(),
                    "{\"stream\":true,\"messages\":[]}".getBytes(StandardCharsets.UTF_8));
        }

        /** A Heph turn's call to {@code baseUrl}, which the proxy cuts after {@link #HEPH_IDLE} without a byte. */
        private MockHttpServletResponse proxyHephTurn(String baseUrl) {
            ProxyRouting routing = new ProxyRouting(
                    "mentor-turn:stream",
                    "openai-completions",
                    baseUrl,
                    FundingSource.INSTANCE,
                    7L,
                    8L,
                    9L,
                    new ProxyRouting.BilledAttempt(
                            LlmUsageSourceType.MENTOR_TURN, UUID.randomUUID(), 1, BigDecimal.ZERO, "worker"));
            authenticate(routing);
            stubCredential(
                    routing,
                    new LlmModelResolver.ProxyCredential(
                            baseUrl, "openai-completions", LlmAuthMode.BEARER, "catalog-model", "secret"));
            MockHttpServletResponse response = new MockHttpServletResponse();
            controllerProxy(response);
            return response;
        }

        private MockHttpServletResponse proxyHephTurn(MockResponse answer) {
            upstream.enqueue(answer);
            return proxyHephTurn(upstream.url("/v1").toString());
        }

        @Test
        @DisplayName("keep-alive comments keep a slow Heph call alive past the idle bound until it answers")
        void keepAliveCommentsKeepASlowHephCallAlive() throws Exception {
            // A gateway that schedules the answer: comments for about 1.5 seconds, nearly 4 idle bounds, then the
            // whole answer at once.
            HttpServer gateway = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            gateway.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream body = exchange.getResponseBody()) {
                    for (int comment = 0; comment < 10; comment++) {
                        body.write(": scheduled\n\n".getBytes(StandardCharsets.UTF_8));
                        body.flush();
                        Thread.sleep(150);
                    }
                    body.write(
                            ("data: {\"choices\":[{\"delta\":{\"content\":\"late answer\"}}]}\n\n" + "data: [DONE]\n\n")
                                    .getBytes(StandardCharsets.UTF_8));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            gateway.start();
            try {
                MockHttpServletResponse response =
                        proxyHephTurn("http://127.0.0.1:" + gateway.getAddress().getPort() + "/v1");

                assertThat(response.getContentAsString())
                        .contains("late answer")
                        .contains("[DONE]")
                        .doesNotContain("upstream_stalled");
            } finally {
                gateway.stop(0);
            }
        }

        @Test
        @DisplayName("a provider that queues a Heph call past the idle bound still answers within the first-byte bound")
        void aQueuedHephCallAnswersWithinTheFirstByteBound() throws Exception {
            MockHttpServletResponse response = proxyHephTurn(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .headersDelay(800, TimeUnit.MILLISECONDS)
                    .body("data: {\"choices\":[{\"delta\":{\"content\":\"after the queue\"}}]}\n\n"
                            + "data: [DONE]\n\n")
                    .build());

            assertThat(response.getContentAsString())
                    .contains("after the queue")
                    .doesNotContain("upstream_stalled");
        }

        @Test
        @DisplayName("a Heph call whose provider goes silent after its headers ends with a stall error event")
        void aSilentHephCallEndsWithAStallErrorEvent() throws Exception {
            MockHttpServletResponse response = proxyHephTurn(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body("data: {\"choices\":[{\"delta\":{\"content\":\"Let me\"}}]}\n\n")
                    .bodyDelay(3, TimeUnit.SECONDS)
                    .build());

            JsonNode last = OBJECT_MAPPER.readTree(response.getContentAsString()
                    .strip()
                    .lines()
                    .reduce((first, second) -> second)
                    .orElseThrow()
                    .substring("data: ".length()));
            assertThat(last.path("error").path("code").asString()).isEqualTo("upstream_stalled");
            assertThat(last.path("error").path("message").asString()).contains("stream sent nothing for 0 seconds");
            assertThat(streamingMeterRegistry
                            .counter("llm.proxy.upstream.stalled", "apiProtocol", "openai-completions")
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("a provider that goes silent inside an event ends with a stall event the client can read")
        void aStallInsideAnEventEndsWithAWholeStallEvent() throws Exception {
            // A gateway that flushes at a buffer boundary: one whole event, then half of the next, then nothing.
            HttpServer gateway = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            gateway.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(("data: {\"choices\":[{\"delta\":{\"content\":\"Let me\"}}]}\n\n"
                                    + "data: {\"choices\":[{\"delta\":{\"con")
                            .getBytes(StandardCharsets.UTF_8));
                    body.flush();
                    Thread.sleep(3_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException proxyLeft) {
                    // The proxy cut the call at its idle bound.
                }
            });
            gateway.start();
            try {
                MockHttpServletResponse response =
                        proxyHephTurn("http://127.0.0.1:" + gateway.getAddress().getPort() + "/v1");

                List<String> events = List.of(response.getContentAsString().split("\n\n"));
                assertThat(events).hasSize(2);
                for (String event : events) {
                    assertThat(event).startsWith("data: ");
                    // Every event the client receives is whole JSON.
                    OBJECT_MAPPER.readTree(event.substring("data: ".length()));
                }
                assertThat(OBJECT_MAPPER
                                .readTree(events.get(1).substring("data: ".length()))
                                .path("error")
                                .path("code")
                                .asString())
                        .isEqualTo("upstream_stalled");
            } finally {
                gateway.stop(0);
            }
        }

        @Test
        @DisplayName("a stream with CR line endings keeps its whole events when it stalls after one")
        void aCarriageReturnStreamKeepsItsWholeEventsWhenItStalls() throws Exception {
            HttpServer gateway = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            gateway.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write("data: {\"choices\":[{\"delta\":{\"content\":\"Let me\"}}]}\r\r"
                            .getBytes(StandardCharsets.UTF_8));
                    body.flush();
                    Thread.sleep(3_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException proxyLeft) {
                    // The proxy cut the call at its idle bound.
                }
            });
            gateway.start();
            try {
                String relayed = proxyHephTurn(
                                "http://127.0.0.1:" + gateway.getAddress().getPort() + "/v1")
                        .getContentAsString();

                assertThat(relayed).startsWith("data: {\"choices\":[{\"delta\":{\"content\":\"Let me\"}}]}\r\r");
                assertThat(relayed).contains("upstream_stalled");
            } finally {
                gateway.stop(0);
            }
        }

        @Test
        @DisplayName("a Heph call whose provider sends no headers gets a gateway timeout the host can name")
        void aHephCallWithoutHeadersGetsAGatewayTimeout() throws Exception {
            upstream.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .headersDelay(3, TimeUnit.SECONDS)
                    .body("data: [DONE]\n\n")
                    .build());
            ProxyRouting routing = new ProxyRouting(
                    "mentor-turn:headers",
                    "openai-completions",
                    upstream.url("/v1").toString(),
                    FundingSource.INSTANCE,
                    7L,
                    8L,
                    9L,
                    new ProxyRouting.BilledAttempt(
                            LlmUsageSourceType.MENTOR_TURN, UUID.randomUUID(), 1, BigDecimal.ZERO, "worker"));
            authenticate(routing);
            stubCredential(
                    routing,
                    new LlmModelResolver.ProxyCredential(
                            upstream.url("/v1").toString(),
                            "openai-completions",
                            LlmAuthMode.BEARER,
                            "catalog-model",
                            "secret"));

            ResponseEntity<?> result = streamingController.proxy(
                    request("POST", "/internal/llm/chat/completions"),
                    new MockHttpServletResponse(),
                    new HttpHeaders(),
                    "{\"stream\":true,\"messages\":[]}".getBytes(StandardCharsets.UTF_8));

            assertThat(Objects.requireNonNull(result).getStatusCode().value()).isEqualTo(504);
            assertThat(OBJECT_MAPPER
                            .readTree(String.valueOf(result.getBody()))
                            .path("error")
                            .path("code")
                            .asString())
                    .isEqualTo("upstream_stalled");
            assertThat(String.valueOf(result.getBody())).contains("no first byte within 1 seconds");
        }

        @Test
        @DisplayName("cancelling an attempt's calls ends a call whose provider sends nothing at once")
        void cancellingTheAttemptsCallsEndsASilentCallAtOnce() throws Exception {
            upstream.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body("data: {\"choices\":[{\"delta\":{\"content\":\"Let me\"}}]}\n\n")
                    .bodyDelay(30, TimeUnit.SECONDS)
                    .build());
            ProxyRouting routing = streamingRouting();
            authenticate(routing);
            stubCredential(
                    routing,
                    new LlmModelResolver.ProxyCredential(
                            upstream.url("/v1").toString(),
                            "openai-completions",
                            LlmAuthMode.BEARER,
                            "catalog-model",
                            "secret"));
            MockHttpServletResponse response = new MockHttpServletResponse();
            var security = SecurityContextHolder.getContext();
            CompletableFuture<Void> call = CompletableFuture.runAsync(() -> {
                SecurityContextHolder.setContext(security);
                controllerProxy(response);
            });
            upstream.takeRequest(5, TimeUnit.SECONDS);
            Thread.sleep(200);
            long cancelledAt = System.nanoTime();

            streamingCalls.seal(ATTEMPT.sourceId(), ATTEMPT.number(), true);

            call.get(5, TimeUnit.SECONDS);
            assertThat(Duration.ofNanos(System.nanoTime() - cancelledAt)).isLessThan(Duration.ofSeconds(2));
            assertThat(streamingCalls.awaitNone(ATTEMPT.sourceId(), ATTEMPT.number(), Duration.ZERO))
                    .isTrue();
        }

        @Test
        @DisplayName("a call that begins after its attempt's end sealed it never reaches the provider")
        void aCallThatBeginsAfterTheSealNeverReachesTheProvider() throws Exception {
            CountDownLatch authenticated = new CountDownLatch(1);
            CountDownLatch sealed = new CountDownLatch(1);
            // The pause between authentication and registration, where a stop can end the attempt.
            ProxyCallsInFlight calls = new ProxyCallsInFlight() {
                @Override
                public Call begin(ProxyRouting.BilledAttempt billed) {
                    authenticated.countDown();
                    try {
                        sealed.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return super.begin(billed);
                }
            };
            LlmProxyService service = new LlmProxyService(
                    Tracer.NOOP,
                    WebClient.builder().build(),
                    resolver,
                    OBJECT_MAPPER,
                    new ProxyAccounting(
                            budgetGate,
                            usageAccumulator,
                            mentorTurnUsageAccumulator,
                            streamingMeterRegistry,
                            OBJECT_MAPPER),
                    requestPolicy,
                    calls,
                    new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults()),
                    HEPH_FIRST_BYTE,
                    HEPH_IDLE);
            authenticate(streamingRouting());
            var security = SecurityContextHolder.getContext();
            CompletableFuture<@Nullable ResponseEntity<?>> call = CompletableFuture.supplyAsync(() -> {
                SecurityContextHolder.setContext(security);
                return service.proxy(
                        request("POST", "/internal/llm/chat/completions"),
                        new MockHttpServletResponse(),
                        new HttpHeaders(),
                        "{\"stream\":true,\"messages\":[]}".getBytes(StandardCharsets.UTF_8));
            });
            assertThat(authenticated.await(5, TimeUnit.SECONDS)).isTrue();

            calls.seal(ATTEMPT.sourceId(), ATTEMPT.number(), true);
            assertThat(calls.awaitNone(ATTEMPT.sourceId(), ATTEMPT.number(), Duration.ZERO))
                    .isTrue();
            sealed.countDown();

            ResponseEntity<?> result = Objects.requireNonNull(call.get(5, TimeUnit.SECONDS));
            assertThat(result.getStatusCode().value()).isEqualTo(409);
            assertThat(upstream.getRequestCount()).isZero();
        }

        @Test
        @DisplayName("a streamed call is billed for the usage its final frame reports")
        void aStreamedCallIsBilled() throws Exception {
            MockHttpServletResponse response = proxyStream(
                    "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}],\"usage\":null}",
                    "{\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":40,"
                            + "\"prompt_tokens_details\":{\"cached_tokens\":25}}}",
                    "[DONE]");

            assertThat(response.getContentAsString())
                    .contains("\"content\":\"hi\"")
                    .contains("[DONE]");
            ArgumentCaptor<ProxyTokenUsage> usage = ArgumentCaptor.forClass(ProxyTokenUsage.class);
            verify(usageAccumulator).accumulate(eq(ATTEMPT), usage.capture());
            assertThat(usage.getValue().billableInputTokens()).isEqualTo(75);
            assertThat(usage.getValue().outputTokens()).isEqualTo(40);
            assertThat(usage.getValue().cacheReadTokens()).isEqualTo(25);
            assertThat(missingUsage()).isZero();
        }

        @Test
        @DisplayName("a stream that terminates before the usage frame records nothing and is counted as missing")
        void aTruncatedStreamRecordsNothing() throws Exception {
            MockHttpServletResponse response =
                    proxyStream("{\"choices\":[{\"delta\":{\"content\":\"cut off here\"}}],\"usage\":null}");

            assertThat(response.getContentAsString()).contains("cut off here");
            verifyNoInteractions(usageAccumulator);
            assertThat(missingUsage()).isEqualTo(1.0);
        }

        @Test
        void malformedStreamUsageIsCounted() throws Exception {
            proxyStream("{\"usage\":{\"prompt_tokens\":1,\"prompt_tokens_details\":{\"cached_tokens\":2}}}");

            assertThat(streamingMeterRegistry
                            .counter("llm.proxy.usage.unparseable", "sourceType", "AGENT_JOB")
                            .count())
                    .isEqualTo(1.0);
            assertThat(missingUsage())
                    .as("malformed usage is not also counted as absent")
                    .isZero();
            verifyNoInteractions(usageAccumulator);
        }

        private double missingUsage() {
            return streamingMeterRegistry
                    .counter("llm.proxy.usage.missing", "sourceType", "AGENT_JOB")
                    .count();
        }

        @Test
        @DisplayName("the outgoing streaming request asks the provider to report usage")
        void theOutgoingRequestAsksForUsage() throws Exception {
            proxyStream("[DONE]");

            RecordedRequest sent = Objects.requireNonNull(upstream.takeRequest(5, TimeUnit.SECONDS));
            assertThat(OBJECT_MAPPER
                            .readTree(Objects.requireNonNull(sent.getBody()).utf8())
                            .path("stream_options")
                            .path("include_usage")
                            .asBoolean())
                    .isTrue();
        }

        @Test
        @DisplayName("a provider that rejects stream_options degrades to an unmetered call, not a failure")
        void aProviderThatRejectsTheUsageRequestDegrades() throws Exception {
            upstream.enqueue(new MockResponse.Builder()
                    .code(400)
                    .addHeader("Content-Type", "application/json")
                    .body("{\"error\":{\"message\":\"Unrecognized request argument: stream_options\"}}")
                    .build());
            upstream.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body("data: {\"choices\":[{\"delta\":{\"content\":\"served anyway\"}}]}\n\n")
                    .build());
            ProxyRouting routing = streamingRouting();
            authenticate(routing);
            stubCredential(
                    routing,
                    new LlmModelResolver.ProxyCredential(
                            upstream.url("/v1").toString(),
                            "openai-completions",
                            LlmAuthMode.BEARER,
                            "catalog-model",
                            "secret"));
            MockHttpServletResponse response = new MockHttpServletResponse();

            controllerProxy(response);

            assertThat(response.getContentAsString()).contains("served anyway");
            RecordedRequest initial = Objects.requireNonNull(upstream.takeRequest(5, TimeUnit.SECONDS));
            RecordedRequest retry = Objects.requireNonNull(upstream.takeRequest(5, TimeUnit.SECONDS));
            assertThat(Objects.requireNonNull(initial.getBody()).utf8()).contains("stream_options");
            assertThat(Objects.requireNonNull(retry.getBody()).utf8()).doesNotContain("stream_options");
            // The refusal and the served response without usage are two facts, each counted once.
            assertThat(streamingMeterRegistry
                            .counter("llm.proxy.stream.usage.unsupported", "apiProtocol", "openai-completions")
                            .count())
                    .isEqualTo(1.0);
            assertThat(missingUsage()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a streamed Responses call is billed from its completed event, unchanged on the way out")
        void aStreamedResponsesCallIsBilled() throws Exception {
            upstream.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body("event: response.output_text.delta\n"
                            + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}\n\n"
                            + "event: response.completed\n"
                            + "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":100,"
                            + "\"input_tokens_details\":{\"cached_tokens\":25},\"output_tokens\":40,"
                            + "\"output_tokens_details\":{\"reasoning_tokens\":30}}}}\n\n")
                    .build());
            ProxyRouting routing = new ProxyRouting(
                    "job:stream",
                    "openai-responses",
                    upstream.url("/v1").toString(),
                    FundingSource.INSTANCE,
                    7L,
                    8L,
                    9L,
                    ATTEMPT);
            authenticate(routing);
            stubCredential(
                    routing,
                    new LlmModelResolver.ProxyCredential(
                            upstream.url("/v1").toString(),
                            "openai-responses",
                            LlmAuthMode.BEARER,
                            "catalog-model",
                            "secret"));
            MockHttpServletResponse response = new MockHttpServletResponse();

            streamingController.proxy(
                    request("POST", "/internal/llm/responses"),
                    response,
                    new HttpHeaders(),
                    "{\"stream\":true,\"store\":false,\"include\":[\"reasoning.encrypted_content\"],\"input\":[]}"
                            .getBytes(StandardCharsets.UTF_8));

            assertThat(response.getContentAsString()).contains("\"delta\":\"hi\"");
            RecordedRequest sent = Objects.requireNonNull(upstream.takeRequest(5, TimeUnit.SECONDS));
            assertThat(sent.getUrl().encodedPath()).isEqualTo("/v1/responses");
            String sentBody = Objects.requireNonNull(sent.getBody()).utf8();
            assertThat(sentBody).contains("reasoning.encrypted_content").doesNotContain("stream_options");
            ArgumentCaptor<ProxyTokenUsage> usage = ArgumentCaptor.forClass(ProxyTokenUsage.class);
            verify(usageAccumulator).accumulate(eq(ATTEMPT), usage.capture());
            assertThat(usage.getValue().billableInputTokens()).isEqualTo(75);
            assertThat(usage.getValue().outputTokens()).isEqualTo(40);
            assertThat(usage.getValue().reasoningTokens()).isEqualTo(30);
            assertThat(usage.getValue().cacheReadTokens()).isEqualTo(25);
        }
    }

    @Nested
    class HeaderAllowlist {

        @Test
        void shouldInjectBearerAuthAndDropUnapprovedHeaders() {
            var incoming = incomingHeaders();

            HttpHeaders output =
                    controller.buildUpstreamHeaders(incoming, credential("openai-completions", LlmAuthMode.BEARER));

            assertThat(output.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer secret");
            assertThat(output.get("api-key")).isNull();
            assertThat(output.get("x-api-key")).isNull();
            assertThat(output.get("x-forward-me")).isNull();
            assertThat(output.getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/json");
            assertThat(output.getFirst(HttpHeaders.ACCEPT)).isEqualTo("text/event-stream");
            assertThat(output.getFirst(HttpHeaders.ACCEPT_ENCODING)).isEqualTo("identity");
        }

        @Test
        void shouldInjectRawApiKeyAuth() {
            HttpHeaders output = controller.buildUpstreamHeaders(
                    incomingHeaders(), credential("openai-responses", LlmAuthMode.API_KEY));

            assertThat(output.getFirst("api-key")).isEqualTo("secret");
            assertThat(output.get(HttpHeaders.AUTHORIZATION)).isNull();
        }

        @Test
        void shouldNotInjectAuthWhenKeyIsBlank() {
            var credential = new LlmModelResolver.ProxyCredential(
                    "https://api.example.com/v1", "openai-completions", LlmAuthMode.BEARER, "catalog-model", " ");

            HttpHeaders output = controller.buildUpstreamHeaders(incomingHeaders(), credential);

            assertThat(output.get(HttpHeaders.AUTHORIZATION)).isNull();
            assertThat(output.get("api-key")).isNull();
        }
    }

    @Test
    void shouldBuildCanonicalProtocolUrls() {
        assertThat(LlmProxyService.buildUpstreamUri("https://api.example.com/v1/", "openai-completions"))
                .isEqualTo(URI.create("https://api.example.com/v1/chat/completions"));
        assertThat(LlmProxyService.buildUpstreamUri("https://api.example.com/v1", "openai-responses"))
                .isEqualTo(URI.create("https://api.example.com/v1/responses"));
    }

    private static byte[] jsonBody() {
        return "{\"model\":\"anything\"}".getBytes(StandardCharsets.UTF_8);
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private static HttpHeaders incomingHeaders() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer sandbox-token");
        headers.set("api-key", "sandbox-token");
        headers.set("x-api-key", "sandbox-token");
        headers.set("x-forward-me", "unsafe");
        headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
        headers.set(HttpHeaders.ACCEPT, "text/event-stream");
        return headers;
    }

    /** How long a Heph turn's provider may take to its first byte in these tests. */
    private static final Duration HEPH_FIRST_BYTE = Duration.ofMillis(1500);

    /** How long a Heph turn's stream may send no byte in these tests. */
    private static final Duration HEPH_IDLE = Duration.ofMillis(400);

    private static final ProxyRouting.BilledAttempt ATTEMPT = new ProxyRouting.BilledAttempt(
            LlmUsageSourceType.AGENT_JOB,
            UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
            0,
            BigDecimal.ZERO,
            "worker-1");

    private static ProxyRouting routing(String protocol) {
        return new ProxyRouting(
                "job:test", protocol, "https://frozen.example.com/v1", FundingSource.INSTANCE, 7L, 8L, 9L, ATTEMPT);
    }

    private static ProxyRouting workspaceFundedRouting() {
        return new ProxyRouting(
                "job:test",
                "openai-completions",
                "https://frozen.example.com/v1",
                FundingSource.WORKSPACE,
                7L,
                8L,
                9L,
                ATTEMPT);
    }

    private static LlmModelResolver.ProxyCredential credential(String protocol, LlmAuthMode authMode) {
        return new LlmModelResolver.ProxyCredential(
                "https://api.example.com/v1", protocol, authMode, "catalog-model", "secret");
    }

    private void authenticate(ProxyRouting routing) {
        SecurityContextHolder.getContext().setAuthentication(new JobTokenAuthentication(routing, null));
    }

    private void stubCredential(ProxyRouting routing, LlmModelResolver.ProxyCredential credential) {
        when(resolver.resolveProxyCredential(eq(new LlmModelResolver.ConnectionRef(
                        routing.connectionScope(), routing.connectionId(), routing.modelId(), routing.workspaceId()))))
                .thenReturn(credential);
    }
}
