package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Answers.RETURNS_DEFAULTS;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.LlmProperties;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.catalog.EgressPolicy;
import de.tum.cit.aet.hephaestus.agent.catalog.InstanceLlmSettingsRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionHealth;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.composer.ComposerStreamAnswer;
import de.tum.cit.aet.hephaestus.agent.config.ModelAssignmentLimits;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.ModelCallFailure;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.reactivestreams.Publisher;
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
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class LlmProxyServiceTest extends BaseUnitTest {
    private static final UUID LEASE = UUID.fromString("fafb277e-9c90-58ff-9167-1f1b3b0dce88");
    private static final UUID OTHER_LEASE = UUID.fromString("508d958c-100e-594f-8b4e-6d5c60e59470");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private LlmModelResolver resolver;

    @Mock
    private ProxyRequestPolicy requestPolicy;

    @Mock
    private ProxyBudgetGate budgetGate;

    @Mock
    private ProxyCallLedger callLedger;

    /** The ledger row of the call under test. */
    private static final UUID CALL = UUID.fromString("0f6b9e3a-6c2d-4f43-9b7d-2f5d1a4c8e01");

    @Mock
    private ProxyRoutingService routingService;

    private LlmProxyService controller;
    private SimpleMeterRegistry meterRegistry;

    /** A pool that admits every call, as a model is always in a pool. */
    private static LlmCallPermits admittingPermits() {
        LlmCallPermits permits = mock(LlmCallPermits.class);
        LlmCallPermits.Permit permit = mock(LlmCallPermits.Permit.class);
        lenient().when(permit.revoked()).thenReturn(Mono.never());
        lenient().when(permits.acquire(anyLong(), any(), any(), any(), any())).thenReturn(permit);
        lenient().when(permits.beforeRetry(any(), any(), any())).thenReturn(true);
        LlmPoolLearning learning = mock(LlmPoolLearning.class);
        lenient().when(permits.learning()).thenReturn(learning);
        return permits;
    }

    @BeforeEach
    void setUp() {
        lenient().when(requestPolicy.allows(any())).thenReturn(true);
        lenient().when(routingService.ownsAttempt(any())).thenReturn(true);
        lenient().when(callLedger.open(any(), any(), any(), any())).thenReturn(CALL);
        meterRegistry = new SimpleMeterRegistry();
        controller = new LlmProxyService(
                Tracer.NOOP,
                WebClient.create(),
                resolver,
                OBJECT_MAPPER,
                new ProxyAccounting(budgetGate, callLedger, meterRegistry, OBJECT_MAPPER),
                requestPolicy,
                mock(ScanCallGuard.class),
                routingService,
                new ProxyCallsInFlight(12),
                new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults()),
                admittingPermits(),
                SILENCE_NEVER_REACHED);
    }

    @Test
    void shouldRefuseAnEndedAttemptBeforeCredentialsOrNetworkAccess() {
        var routing = routing("openai-completions");
        authenticate(routing);
        when(routingService.ownsAttempt(routing)).thenReturn(false);
        var result = controller.proxy(
                request("POST", "/internal/llm/chat/completions"),
                new MockHttpServletResponse(),
                new HttpHeaders(),
                jsonBody());
        assertThat(Objects.requireNonNull(result).getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(resolver, budgetGate, callLedger);
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
        assertThat(Objects.requireNonNull(result).getStatusCode().value()).isEqualTo(403);
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
            assertThat(blocked("instance")).isEqualTo(1);
            assertThat(blocked("byo")).isZero();
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
            assertThat(blocked("byo")).isEqualTo(1);
            assertThat(blocked("instance")).isZero();
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
    class OutputLimits {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "{\"stream\":true,\"max_completion_tokens\":0}",
                    "{\"stream\":true,\"max_tokens\":\"x\"}",
                    "{\"stream\":true,\"max_tokens\":1.5}"
                })
        void shouldRefuseACallWhenItsOutputLimitIsNotAPositiveWholeNumberBeforeCredentialsOrNetwork(String body) {
            authenticate(routing("openai-completions"));

            var result = controller.proxy(
                    request("POST", "/internal/llm/chat/completions"),
                    new MockHttpServletResponse(),
                    new HttpHeaders(),
                    body.getBytes(StandardCharsets.UTF_8));

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode().value()).isEqualTo(400);
            assertThat(String.valueOf(result.getBody())).contains("must be a positive whole number");
            verifyNoInteractions(resolver);
            verify(callLedger, never()).open(any(), any(), any(), any());
        }

        @Test
        void shouldRefuseAGenerativeCallWhenItsModelHasNoOutputLimit() {
            authenticate(withOutputLimit(routing("anthropic-messages"), null));

            var result = controller.proxy(
                    request("POST", "/internal/llm/v1/messages"),
                    new MockHttpServletResponse(),
                    new HttpHeaders(),
                    jsonBody());

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode().value()).isEqualTo(400);
            assertThat(String.valueOf(result.getBody())).contains("no output limit");
            verifyNoInteractions(resolver);
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

    @ParameterizedTest
    @CsvSource({
        "COMPLETED, 200, OK",
        "STREAM_FAILED, 200, ERROR",
        "TIMED_OUT, 200, ERROR",
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
            Flux<DataBuffer> body = switch (outcome) {
                case "STREAM_FAILED" -> Flux.error(new IOException("private-provider-token"));
                // A typed timeout cause; the span keeps the closed outcome and never the words.
                case "TIMED_OUT" -> Flux.error(new TimeoutException("private-provider-token"));
                default -> Flux.just(buffer);
            };
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
                    new ProxyAccounting(budgetGate, callLedger, new SimpleMeterRegistry(), OBJECT_MAPPER),
                    requestPolicy,
                    mock(ScanCallGuard.class),
                    routingService,
                    new ProxyCallsInFlight(12),
                    new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults()),
                    admittingPermits(),
                    SILENCE_NEVER_REACHED);
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
        private final ProxyCallsInFlight streamingCalls = new ProxyCallsInFlight(12);
        private final LlmCallPermits streamingPermits = admittingPermits();
        private final CircuitBreakerRegistry streamingBreakers = CircuitBreakerRegistry.ofDefaults();
        private final LlmConnectionHealth streamingHealth = new LlmConnectionHealth(streamingBreakers);

        @BeforeEach
        void startUpstream() throws IOException {
            upstream = new MockWebServer();
            upstream.start();
            streamingMeterRegistry = new SimpleMeterRegistry();
            streamingController = controllerWith(WebClient.builder().build());
        }

        private LlmProxyService controllerWith(WebClient client) {
            return controllerWith(client, Tracer.NOOP);
        }

        private LlmProxyService controllerWith(WebClient client, Tracer tracer) {
            return controllerWith(client, tracer, SILENCE_NEVER_REACHED);
        }

        /** A controller that ends a call on {@code silence}: a test of a silence bound reaches only its own. */
        private LlmProxyService controllerWith(ConversationCallProperties silence) {
            return controllerWith(WebClient.builder().build(), Tracer.NOOP, silence);
        }

        private LlmProxyService controllerWith(WebClient client, Tracer tracer, ConversationCallProperties silence) {
            return new LlmProxyService(
                    tracer,
                    client,
                    resolver,
                    OBJECT_MAPPER,
                    new ProxyAccounting(budgetGate, callLedger, streamingMeterRegistry, OBJECT_MAPPER),
                    requestPolicy,
                    mock(ScanCallGuard.class),
                    routingService,
                    streamingCalls,
                    streamingHealth,
                    streamingPermits,
                    silence);
        }

        @AfterEach
        void stopUpstream() throws IOException {
            upstream.close();
        }

        /**
         * A provider that reads the request and sends nothing, not even its headers, until the test closes it. Reactor
         * Netty owns the first-byte timer, so a test of it needs a real socket; nothing ever arrives to race it.
         */
        private static final class SilentProvider implements AutoCloseable {
            private final CountDownLatch closed = new CountDownLatch(1);
            private final com.sun.net.httpserver.HttpServer server;

            SilentProvider() throws IOException {
                server = com.sun.net.httpserver.HttpServer.create(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                server.createContext("/v1/chat/completions", exchange -> {
                    try {
                        exchange.getRequestBody().readAllBytes();
                        closed.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
                server.start();
            }

            String baseUrl() {
                return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            }

            @Override
            public void close() {
                closed.countDown();
                server.stop(0);
            }
        }

        /**
         * One call to {@code client} on virtual time: the proxy's idle bound, its deadline timer and its deadline checks
         * all read the parallel scheduler's clock, which the virtual one replaces.
         * Virtual time moves only when the test advances it, after the proxy reached the point the test waits for, so
         * no chunk is ever late and no bound or deadline passes early, however slowly the test runs.
         */
        private final class VirtualCall implements AutoCloseable {
            final VirtualTimeScheduler time = VirtualTimeScheduler.getOrSet();
            private final ExecutorService caller = Executors.newSingleThreadExecutor();
            private final Semaphore flushed = new Semaphore(0);
            final MockHttpServletResponse response = flushCounting(flushed);
            private final CompletableFuture<@Nullable ResponseEntity<?>> answer = new CompletableFuture<>();

            VirtualCall(WebClient client, ConversationCallProperties silence) {
                streamingController = controllerWith(client, Tracer.NOOP, silence);
            }

            /** The virtual time now, as the proxy reads it. */
            Instant now() {
                return Instant.ofEpochMilli(time.now(TimeUnit.MILLISECONDS));
            }

            void start(ProxyRouting routing) {
                start(routing, null);
            }

            /** As above, and {@code capture} reads what the proxy relayed, as the feedback composer does. */
            void start(ProxyRouting routing, @Nullable Consumer<byte[]> capture) {
                stubStreamCredential(routing);
                caller.execute(() -> {
                    authenticate(routing);
                    try {
                        HttpHeaders headers = new HttpHeaders();
                        if (routing.attempt().sourceType() == UsageSourceType.PRODUCT_FEEDBACK_DRAFT)
                            headers.set(ProxyRouting.DRAFT_MODEL_HEADER, Objects.toString(routing.modelId()));
                        answer.complete(streamingController.proxy(
                                request("POST", "/internal/llm/chat/completions"),
                                response,
                                headers,
                                STREAM_REQUEST,
                                capture));
                    } catch (RuntimeException | Error failure) {
                        answer.completeExceptionally(failure);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            }

            /**
             * Waits until the proxy relayed the provider's next chunk. The proxy reads a chunk, which arms the idle
             * timer again, before it writes and flushes it, so a flush is the point after which time may move.
             */
            void relayed() throws InterruptedException {
                assertThat(flushed.tryAcquire(10, TimeUnit.SECONDS)).isTrue();
            }

            @Nullable
            ResponseEntity<?> finished() throws Exception {
                return answer.get(10, TimeUnit.SECONDS);
            }

            @Override
            public void close() {
                caller.shutdownNow();
                try {
                    assertThat(caller.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                } finally {
                    VirtualTimeScheduler.reset();
                }
            }
        }

        /** A provider whose one answer streams {@code body}. */
        private WebClient streaming(Flux<DataBuffer> body) {
            AtomicBoolean consumed = new AtomicBoolean();
            // An HTTP body is consumed once; the client's cleanup after a failure must not subscribe to it again.
            return answering(
                    new AtomicInteger(), Flux.defer(() -> consumed.compareAndSet(false, true) ? body : Flux.empty()));
        }

        /**
         * A provider that answers its requests with {@code answers} in turn and releases {@code requested} as each one
         * arrives. The proxy has started its deadline's timer by then, so virtual time may move.
         */
        @SafeVarargs
        private static WebClient answeringInTurn(Semaphore requested, Mono<ClientResponse>... answers) {
            AtomicInteger next = new AtomicInteger();
            return WebClient.builder()
                    .exchangeFunction(request -> {
                        Mono<ClientResponse> answer = answers[next.getAndIncrement()];
                        requested.release();
                        return answer;
                    })
                    .build();
        }

        /** What a call of {@code routing} relayed when its provider sent {@code sent}, then nothing for the idle bound. */
        private MockHttpServletResponse stalledAfter(ProxyRouting routing, String sent) throws Exception {
            try (VirtualCall call =
                    new VirtualCall(streaming(Flux.just(sse(sent)).concatWith(Flux.never())), IDLE_ONLY)) {
                call.start(routing);
                call.relayed();
                call.time.advanceTimeBy(CONVERSATION_IDLE);
                call.finished();
                return call.response;
            }
        }

        private ProxyRouting streamingRouting() {
            return streamingRouting(laterThanAnyTest());
        }

        private ProxyRouting streamingRouting(Instant workDeadline) {
            return new ProxyRouting(
                    "job:stream",
                    "openai-completions",
                    upstream.url("/v1").toString(),
                    UsageFundingSource.INSTANCE,
                    7L,
                    8L,
                    9L,
                    ATTEMPT,
                    workDeadline,
                    4096L);
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
                            "secret",
                            42L));
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

        private ProxyRouting conversationTurnRouting(String baseUrl) {
            return new ProxyRouting(
                    "conversation-turn:stream",
                    "openai-completions",
                    baseUrl,
                    UsageFundingSource.INSTANCE,
                    7L,
                    8L,
                    9L,
                    new ProxyRouting.BilledAttempt(UsageSourceType.CONVERSATION_TURN, UUID.randomUUID(), 1, LEASE),
                    null,
                    4096L);
        }

        /** A conversation turn's call to {@code baseUrl}, through the current {@code streamingController}. */
        private MockHttpServletResponse proxyConversationTurn(String baseUrl) {
            ProxyRouting routing = conversationTurnRouting(baseUrl);
            authenticate(routing);
            stubCredential(
                    routing,
                    new LlmModelResolver.ProxyCredential(
                            baseUrl, "openai-completions", LlmAuthMode.BEARER, "catalog-model", "secret", 42L));
            MockHttpServletResponse response = new MockHttpServletResponse();
            controllerProxy(response);
            return response;
        }

        private MockHttpServletResponse proxyConversationTurn(MockResponse answer) {
            upstream.enqueue(answer);
            return proxyConversationTurn(upstream.url("/v1").toString());
        }

        @Test
        @DisplayName("keep-alive comments keep a slow Hephaestus call alive past the idle bound until it answers")
        void keepAliveCommentsKeepASlowConversationCallAlive() throws Exception {
            // Each comment comes 100 ms inside the idle bound; together they span nearly 4 idle bounds.
            Sinks.Many<DataBuffer> provider = Sinks.many().unicast().onBackpressureBuffer();
            try (VirtualCall call = new VirtualCall(streaming(provider.asFlux()), IDLE_ONLY)) {
                call.start(conversationTurnRouting(upstream.url("/v1").toString()));
                for (int comment = 0; comment < 5; comment++) {
                    provider.emitNext(sse(": scheduled\n\n"), Sinks.EmitFailureHandler.FAIL_FAST);
                    call.relayed();
                    call.time.advanceTimeBy(CONVERSATION_IDLE.minusMillis(100));
                }
                provider.emitNext(
                        sse("data: {\"choices\":[{\"delta\":{\"content\":\"late answer\"}}]}\n\n" + "data: [DONE]\n\n"),
                        Sinks.EmitFailureHandler.FAIL_FAST);
                provider.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);

                call.finished();
                assertThat(call.response.getContentAsString())
                        .contains("late answer")
                        .contains("[DONE]")
                        .doesNotContain("upstream_stalled");
            }
        }

        private static DataBuffer sse(String text) {
            return DefaultDataBufferFactory.sharedInstance.wrap(text.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void aReviewStreamWithOnlyNonContentFramesEndsAndRecordsAnOutage() throws Exception {
            Sinks.Many<DataBuffer> provider = Sinks.many().unicast().onBackpressureBuffer();
            try (VirtualCall call = new VirtualCall(streaming(provider.asFlux()), IDLE_ONLY)) {
                doAnswer(invocation -> {
                            assertThat(call.response.getContentAsString()).doesNotContain("upstream_stalled:");
                            return null;
                        })
                        .when(callLedger)
                        .complete(
                                any(),
                                eq(CALL),
                                any(),
                                eq(new ModelCallFailure(LlmProxyService.UPSTREAM_STALLED, null)));
                call.start(streamingRouting());
                provider.emitNext(sse(": scheduled\n\n"), Sinks.EmitFailureHandler.FAIL_FAST);
                call.relayed();
                call.time.advanceTimeBy(CONVERSATION_IDLE.minusMillis(100));
                provider.emitNext(
                        sse("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n"),
                        Sinks.EmitFailureHandler.FAIL_FAST);
                call.relayed();
                call.time.advanceTimeBy(Duration.ofMillis(100));

                call.finished();

                assertThat(call.response.getStatus()).isEqualTo(200);
                assertThat(call.response.getContentAsString()).containsOnlyOnce("upstream_stalled:");
                verify(callLedger)
                        .complete(
                                any(),
                                eq(CALL),
                                any(),
                                eq(new ModelCallFailure(LlmProxyService.UPSTREAM_STALLED, null)));
                assertThat(provider.currentSubscriberCount()).isZero();
            }
        }

        @Test
        void aReviewStreamsPastTheFirstContentBoundAfterACarriageReturnEvent() throws Exception {
            Sinks.Many<DataBuffer> provider = Sinks.many().unicast().onBackpressureBuffer();
            try (VirtualCall call = new VirtualCall(streaming(provider.asFlux()), IDLE_ONLY)) {
                call.start(streamingRouting());
                provider.emitNext(
                        sse("data: {\"choices\":[{\"delta\":{\"content\":\"first answer\"}}]}\r\r"),
                        Sinks.EmitFailureHandler.FAIL_FAST);
                call.relayed();
                call.time.advanceTimeBy(CONVERSATION_IDLE.minusMillis(100));
                provider.emitNext(sse(": keep alive\r\r"), Sinks.EmitFailureHandler.FAIL_FAST);
                call.relayed();
                call.time.advanceTimeBy(Duration.ofMillis(100));
                provider.emitNext(sse("data: [DONE]\r\r"), Sinks.EmitFailureHandler.FAIL_FAST);
                provider.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);

                call.finished();

                assertThat(call.response.getContentAsString())
                        .contains("first answer", "[DONE]")
                        .doesNotContain("upstream_stalled");
            }
        }

        /** A servlet response that releases {@code flushed} each time the proxy flushes what it relayed. */
        private static MockHttpServletResponse flushCounting(Semaphore flushed) {
            return new MockHttpServletResponse() {
                @Override
                public ServletOutputStream getOutputStream() {
                    ServletOutputStream relayed = super.getOutputStream();
                    return new ServletOutputStream() {
                        @Override
                        public boolean isReady() {
                            return true;
                        }

                        @Override
                        public void setWriteListener(WriteListener listener) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public void write(int value) throws IOException {
                            relayed.write(value);
                        }

                        @Override
                        public void write(byte[] bytes, int offset, int length) throws IOException {
                            relayed.write(bytes, offset, length);
                        }

                        @Override
                        public void flush() throws IOException {
                            relayed.flush();
                            flushed.release();
                        }
                    };
                }
            };
        }

        @Test
        @DisplayName(
                "a review stream whose provider sends its headers and one comment, then nothing, ends at the idle bound")
        void aReviewStreamThatGoesSilentAfterAKeepAliveCommentEndsAtTheIdleBound() throws Exception {
            // The shape of a gateway that schedules the call and never answers: headers, one comment, then silence.
            // The attempt's deadline is far off, so only the idle bound can end the call.
            LlmCallPermits.Permit permit = mock(LlmCallPermits.Permit.class);
            when(permit.revoked()).thenReturn(Mono.never());
            when(routingService.band(any())).thenReturn(AgentRunBand.BATCH);
            when(streamingPermits.acquire(eq(42L), eq(AgentRunBand.BATCH), any(), any(), any()))
                    .thenReturn(permit);

            MockHttpServletResponse response = stalledAfter(streamingRouting(), ": logos-schedule\n\n");

            assertThat(response.getStatus()).isEqualTo(200);
            JsonNode last = OBJECT_MAPPER.readTree(response.getContentAsString()
                    .strip()
                    .lines()
                    .reduce((first, second) -> second)
                    .orElseThrow()
                    .substring("data: ".length()));
            assertThat(last.path("error").path("code").asString()).isEqualTo("upstream_stalled");
            // The review runner's model client retries an error that says it timed out.
            assertThat(last.path("error").path("message").asString())
                    .contains("timed out: it sent no model content for 0 seconds");
            assertThat(streamingMeterRegistry
                            .counter("llm.proxy.upstream.stalled", "apiProtocol", "openai-completions")
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("a sandbox call that does not stream is refused before credentials or network access")
        void aSandboxCallWithoutAStreamIsRefused() {
            ProxyRouting routing = streamingRouting();
            authenticate(routing);

            ResponseEntity<?> answer = streamingController.proxy(
                    request("POST", "/internal/llm/chat/completions"),
                    new MockHttpServletResponse(),
                    new HttpHeaders(),
                    "{\"stream\":false,\"messages\":[]}".getBytes(StandardCharsets.UTF_8));

            assertThat(answer).isNotNull();
            assertThat(answer.getStatusCode().value()).isEqualTo(400);
            assertThat(answer.getHeaders().getFirst(LlmProxyService.REFUSAL_HEADER))
                    .isEqualTo(ProxyRefusal.NOT_STREAMED.name());
            assertThat(upstream.getRequestCount()).isZero();
        }

        @Test
        @DisplayName(
                "a composer stream that keeps sending runs many times past the idle bound and keeps its whole answer")
        void aLongComposerStreamCompletesWithItsAnswer() throws Exception {
            // Each piece comes 100 ms inside the idle bound; together they span many idle bounds.
            List<String> pieces = new ArrayList<>();
            pieces.add("data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}\n\n");
            for (int step = 0; step < 12; step++)
                pieces.add("data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"step " + step
                        + ". \"}}]}\n\n");
            pieces.add("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                    + "\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"check_package\","
                    + "\"arguments\":\"{}\"}}]}}]}\n\n");
            pieces.add("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n");
            Sinks.Many<DataBuffer> provider = Sinks.many().unicast().onBackpressureBuffer();
            var answer = new ComposerStreamAnswer(LlmApiProtocol.OPENAI_COMPLETIONS, OBJECT_MAPPER);
            MockHttpServletResponse response;
            try (VirtualCall call = new VirtualCall(streaming(provider.asFlux()), IDLE_ONLY)) {
                call.start(streamingRouting(), answer);
                for (String piece : pieces) {
                    provider.emitNext(sse(piece), Sinks.EmitFailureHandler.FAIL_FAST);
                    call.relayed();
                    call.time.advanceTimeBy(CONVERSATION_IDLE.minusMillis(100));
                }
                provider.emitNext(sse("data: [DONE]\n\n"), Sinks.EmitFailureHandler.FAIL_FAST);
                provider.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);
                call.finished();
                response = call.response;
            }

            assertThat(response.getContentAsString()).doesNotContain(LlmProxyService.UPSTREAM_STALLED);
            JsonNode message = Objects.requireNonNull(answer.answer())
                    .path("choices")
                    .path(0)
                    .path("message");
            assertThat(message.path("reasoning_content").asString())
                    .startsWith("step 0. ")
                    .endsWith("step 11. ");
            assertThat(message.path("tool_calls")
                            .path(0)
                            .path("function")
                            .path("name")
                            .asString())
                    .isEqualTo("check_package");
        }

        @Test
        @DisplayName(
                "a provider that queues a Hephaestus call past the idle bound still answers within the first-byte bound")
        void aQueuedConversationCallAnswersWithinTheFirstByteBound() throws Exception {
            streamingController = controllerWith(IDLE_ONLY);
            MockHttpServletResponse response = proxyConversationTurn(new MockResponse.Builder()
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

        /** What the caller received: the servlet response a stream wrote, else the answer the proxy returned. */
        private record Answer(int status, String body) {}

        /** A review's stream on a model in pool 42, whose permit the mocked pool hands out as {@code permit}. */
        private Answer proxyPooledStream(String baseUrl, LlmCallPermits.@Nullable Permit permit) throws Exception {
            return proxyPooledStream(baseUrl, permit, new MockHttpServletResponse());
        }

        private Answer proxyPooledStream(
                String baseUrl, LlmCallPermits.@Nullable Permit permit, MockHttpServletResponse response)
                throws Exception {
            ProxyRouting routing = streamingRouting();
            authenticate(routing);
            stubCredential(
                    routing,
                    new LlmModelResolver.ProxyCredential(
                            baseUrl, "openai-completions", LlmAuthMode.BEARER, "catalog-model", "secret", 42L));
            when(routingService.band(any())).thenReturn(AgentRunBand.BATCH);
            when(streamingPermits.acquire(eq(42L), eq(AgentRunBand.BATCH), any(), any(), any()))
                    .thenReturn(permit);
            ResponseEntity<?> answer = streamingController.proxy(
                    request("POST", "/internal/llm/chat/completions"),
                    response,
                    new HttpHeaders(),
                    "{\"stream\":true,\"messages\":[]}".getBytes(StandardCharsets.UTF_8));
            if (answer == null) return new Answer(response.getStatus(), response.getContentAsString());
            Object body = answer.getBody();
            String text =
                    body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(body);
            return new Answer(answer.getStatusCode().value(), text);
        }

        @Test
        void shouldRefuseChangedEligibilityBeforeEgressWithoutOpeningUsage() throws Exception {
            var permit = mock(LlmCallPermits.Permit.class);
            when(permit.revoked()).thenReturn(Mono.never());
            when(requestPolicy.allows(any())).thenReturn(true, false);
            var answer = proxyPooledStream(upstream.url("/v1").toString(), permit);
            assertThat(answer.status()).isEqualTo(403);
            assertThat(upstream.getRequestCount()).isZero();
            verify(callLedger, never()).open(any(), any(), any(), any());
        }

        /** The hold: the ledger refuses to open a call whose upper bound does not fit under its purse's cap. */
        @Test
        void shouldRefuseACallWhoseUpperBoundDoesNotFitUnderTheCapBeforeItReachesItsProvider() throws Exception {
            var permit = mock(LlmCallPermits.Permit.class);
            when(permit.revoked()).thenReturn(Mono.never());
            when(callLedger.open(any(), any(), any(), any())).thenThrow(new ProxyCallLedger.BudgetHeld());

            var answer = proxyPooledStream(upstream.url("/v1").toString(), permit);

            assertThat(answer.status()).isEqualTo(429);
            assertThat(answer.body()).contains("Shared-model budget reached");
            assertThat(upstream.getRequestCount()).isZero();
            verify(callLedger, never()).complete(any(), any(), any(), any());
        }

        @Test
        void shouldBillACompletedJsonAnswerWhenItsPermitIsRevokedBeforeSettlement() throws Exception {
            var routing = streamingRouting();
            Objects.requireNonNull(streamingHealth.acquire(routing.connectionScope(), routing.connectionId()))
                    .released();
            var breaker = streamingBreakers.circuitBreaker("llm-connection-INSTANCE-7");
            breaker.transitionToOpenState();
            breaker.transitionToHalfOpenState();
            var permit = mock(LlmCallPermits.Permit.class);
            when(permit.revoked()).thenReturn(Mono.never());
            AtomicBoolean revoked = new AtomicBoolean();
            when(permit.isRevoked()).thenAnswer(_ -> revoked.get());
            LlmPoolLearning learning = streamingPermits.learning();
            doAnswer(_ -> {
                        revoked.set(true);
                        return null;
                    })
                    .when(learning)
                    .served(eq(permit), any(), anyBoolean());
            upstream.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body("{\"choices\":[{\"message\":{\"content\":\"complete\"}}],"
                            + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}")
                    .build());

            var answer = proxyPooledStream(upstream.url("/v1").toString(), permit);

            assertThat(answer.status()).isEqualTo(200);
            assertThat(answer.body()).contains("complete");
            ArgumentCaptor<LlmUsageRecorder.CallUsage> usage =
                    ArgumentCaptor.forClass(LlmUsageRecorder.CallUsage.class);
            verify(callLedger).complete(any(), eq(CALL), usage.capture(), any());
            assertThat(usage.getValue().inputTokens()).isEqualTo(12);
            assertThat(usage.getValue().outputTokens()).isEqualTo(3);
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            verify(permit).close();
        }

        private LlmCallPermits.Permit throttledPermit(@Nullable Duration asked) {
            LlmCallPermits.Permit permit = mock(LlmCallPermits.Permit.class);
            when(permit.revoked()).thenReturn(Mono.never());
            when(streamingPermits.learning().throttled(eq(permit), anyInt(), any(), anyBoolean()))
                    .thenReturn(asked);
            return permit;
        }

        private MockResponse refused(int status, String body, String... retryAfter) {
            var response = new MockResponse.Builder()
                    .code(status)
                    .addHeader("Content-Type", "application/json")
                    .body(body);
            for (String value : retryAfter) response.addHeader("Retry-After", value);
            return response.build();
        }

        private MockResponse servedAnswer() {
            return new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body("{\"choices\":[{\"message\":{\"content\":\"complete\"}}]}")
                    .build();
        }

        @Test
        @DisplayName("a rate limit answer lowers the pool's limit and the caller only waits")
        void aRateLimitAnswerSlowsTheCallerWithoutFailingIt() throws Exception {
            LlmCallPermits.Permit permit = throttledPermit(Duration.ofSeconds(3));
            upstream.enqueue(refused(429, "{\"error\":{\"message\":\"busy\"}}", "3"));
            upstream.enqueue(servedAnswer());

            Answer answer = proxyPooledStream(upstream.url("/v1").toString(), permit);

            assertThat(answer.status()).isEqualTo(200);
            assertThat(answer.body()).contains("complete");
            assertThat(upstream.getRequestCount()).isEqualTo(2);
            verify(streamingPermits.learning())
                    .throttled(
                            eq(permit),
                            eq(429),
                            argThat(headers -> "3".equals(headers.getFirst("Retry-After"))),
                            eq(true));
            verify(permit).absorbed();
            verify(permit).served(true);
            verify(permit, times(2)).close();
        }

        @Test
        @DisplayName("an overload answer lowers the pool's limit, and the connection's breaker hears nothing")
        void anOverloadAnswerSlowsTheCallerWithoutFailingIt() throws Exception {
            LlmCallPermits.Permit permit = throttledPermit(Duration.ofSeconds(5));
            upstream.enqueue(refused(529, "{\"error\":{\"type\":\"overloaded_error\"}}"));
            upstream.enqueue(refused(503, "{\"error\":{\"message\":\"busy\"}}", "1"));
            upstream.enqueue(servedAnswer());

            Answer answer = proxyPooledStream(upstream.url("/v1").toString(), permit);

            assertThat(answer.status()).isEqualTo(200);
            assertThat(upstream.getRequestCount()).isEqualTo(3);
            assertThat(streamingBreakers
                            .circuitBreaker("llm-connection-INSTANCE-7")
                            .getMetrics()
                            .getNumberOfFailedCalls())
                    .isZero();
        }

        @Test
        @DisplayName("a pause past the wait a call can take reaches the caller as the provider's answer")
        void aLongPauseReachesTheCaller() throws Exception {
            LlmCallPermits.Permit permit = throttledPermit(Duration.ofHours(1));
            upstream.enqueue(refused(429, "{\"error\":{\"message\":\"daily limit\"}}", "3600"));

            Answer answer = proxyPooledStream(upstream.url("/v1").toString(), permit);

            assertThat(answer.status()).isEqualTo(429);
            assertThat(answer.body()).contains("daily limit");
            assertThat(upstream.getRequestCount()).isEqualTo(1);
            verify(permit, never()).absorbed();
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "{\"error\":{\"code\":\"insufficient_quota\"}}",
                    "{\"error\":{\"details\":{\"error_code\":\"enforced_spend_limit_reached\"}}}"
                })
        @DisplayName("a spent account reaches the caller, as no wait mends it")
        void aSpentAccountReachesTheCaller(String body) throws Exception {
            LlmCallPermits.Permit permit = mock(LlmCallPermits.Permit.class);
            when(permit.revoked()).thenReturn(Mono.never());
            upstream.enqueue(refused(429, body, "1"));

            Answer answer = proxyPooledStream(upstream.url("/v1").toString(), permit);

            assertThat(answer.status()).isEqualTo(429);
            assertThat(upstream.getRequestCount()).isEqualTo(1);
            // It says nothing about capacity: no other caller slows down, and the limit stays.
            verify(streamingPermits.learning(), never()).throttled(any(), anyInt(), any(), anyBoolean());
        }

        @Test
        @DisplayName("a 503 that names no pause may be an outage: the caller and the breaker hear it at once")
        void anOutageReachesTheCallerAndTheBreaker() throws Exception {
            LlmCallPermits.Permit permit = throttledPermit(Duration.ofSeconds(5));
            upstream.enqueue(refused(503, "{\"error\":{\"message\":\"down\"}}"));

            Answer answer = proxyPooledStream(upstream.url("/v1").toString(), permit);

            assertThat(answer.status()).isEqualTo(503);
            assertThat(upstream.getRequestCount()).isEqualTo(1);
            assertThat(streamingBreakers
                            .circuitBreaker("llm-connection-INSTANCE-7")
                            .getMetrics()
                            .getNumberOfFailedCalls())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a workspace credential's limit in a pool that models share reaches only its caller")
        void aWorkspaceCredentialInASharedPoolReachesItsCaller() throws Exception {
            LlmCallPermits.Permit permit = throttledPermit(null);
            upstream.enqueue(refused(429, "{\"error\":{\"message\":\"busy\"}}", "1"));

            Answer answer = proxyPooledStream(upstream.url("/v1").toString(), permit);

            assertThat(answer.status()).isEqualTo(429);
            assertThat(upstream.getRequestCount()).isEqualTo(1);
            verify(permit, never()).absorbed();
        }

        @Test
        @DisplayName("a client error says nothing about capacity, and the limit stays")
        void aClientErrorLeavesTheLimitAlone() throws Exception {
            LlmCallPermits.Permit permit = throttledPermit(null);
            upstream.enqueue(refused(400, "{\"error\":{\"message\":\"bad request\"}}"));

            Answer answer = proxyPooledStream(upstream.url("/v1").toString(), permit);

            assertThat(answer.status()).isEqualTo(400);
            verify(streamingPermits.learning(), never()).throttled(any(), anyInt(), any(), anyBoolean());
            verify(permit, never()).served(anyBoolean());
        }

        @Test
        @DisplayName("a call that its pool never admits does not reach the provider")
        void aCallItsPoolNeverAdmitsDoesNotReachTheProvider() throws Exception {
            Answer answer = proxyPooledStream(upstream.url("/v1").toString(), null);

            assertThat(answer.status()).isEqualTo(503);
            assertThat(answer.body()).isEqualTo("The model capacity was unavailable before this attempt ended.");
            assertThat(upstream.getRequestCount()).isZero();
        }

        @Test
        @DisplayName("a call whose worker loses its pool permit stops streaming with an error event")
        void aCallWhoseWorkerLosesItsPermitStops() throws Exception {
            upstream.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body("data: {\"choices\":[{\"delta\":{\"content\":\"late\"}}]}\n\ndata: [DONE]\n\n")
                    .bodyDelay(30, TimeUnit.SECONDS)
                    .build());
            LlmCallPermits.Permit permit = mock(LlmCallPermits.Permit.class);
            // The permit is lost once the proxy has relayed the provider's head, so the stream has begun.
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean revoked = new AtomicBoolean();
            when(permit.revoked())
                    .thenReturn(Flux.interval(Duration.ofMillis(20))
                            .filter(tick -> response.isCommitted())
                            .next()
                            .then()
                            .doOnSuccess(_ -> revoked.set(true)));
            when(permit.isRevoked()).thenAnswer(_ -> revoked.get());

            Answer answer = proxyPooledStream(upstream.url("/v1").toString(), permit, response);

            assertThat(answer.status()).isEqualTo(200);
            assertThat(answer.body()).doesNotContain("[DONE]").contains("upstream_paused");
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> verify(permit).close());
        }

        @Test
        @DisplayName("a call whose worker loses its pool permit before the provider answers gets a provider error")
        void aCallWhosePermitIsLostBeforeTheAnswerGetsAProviderError() throws Exception {
            LlmCallPermits.Permit permit = mock(LlmCallPermits.Permit.class);
            when(permit.revoked()).thenReturn(Mono.empty());
            when(permit.isRevoked()).thenReturn(true);
            upstream.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .headersDelay(5, TimeUnit.SECONDS)
                    .body("data: [DONE]\n\n")
                    .build());

            Answer answer = proxyPooledStream(upstream.url("/v1").toString(), permit);

            assertThat(answer.status()).isEqualTo(503);
            assertThat(answer.body()).contains("upstream_paused");
            verify(permit).close();
        }

        @Test
        @DisplayName("a Hephaestus call whose provider goes silent after its headers ends with a stall error event")
        void aSilentConversationCallEndsWithAStallErrorEvent() throws Exception {
            MockHttpServletResponse response = stalledAfter(
                    conversationTurnRouting(upstream.url("/v1").toString()),
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Let me\"}}]}\n\n");

            JsonNode last = OBJECT_MAPPER.readTree(response.getContentAsString()
                    .strip()
                    .lines()
                    .reduce((first, second) -> second)
                    .orElseThrow()
                    .substring("data: ".length()));
            assertThat(last.path("error").path("code").asString()).isEqualTo("upstream_stalled");
            assertThat(last.path("error").path("message").asString())
                    .contains("stream timed out: it sent nothing for 0 seconds");
            assertThat(streamingMeterRegistry
                            .counter("llm.proxy.upstream.stalled", "apiProtocol", "openai-completions")
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("a provider that goes silent inside an event ends with a stall event the client can read")
        void aStallInsideAnEventEndsWithAWholeStallEvent() throws Exception {
            // A gateway that flushes at a buffer boundary: one whole event, then half of the next, then nothing.
            MockHttpServletResponse response = stalledAfter(
                    conversationTurnRouting(upstream.url("/v1").toString()),
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Let me\"}}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{\"con");

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
        }

        @Test
        @DisplayName("a stream with CR line endings keeps its whole events when it stalls after one")
        void aCarriageReturnStreamKeepsItsWholeEventsWhenItStalls() throws Exception {
            String relayed = stalledAfter(
                            conversationTurnRouting(upstream.url("/v1").toString()),
                            "data: {\"choices\":[{\"delta\":{\"content\":\"Let me\"}}]}\r\r")
                    .getContentAsString();

            assertThat(relayed).startsWith("data: {\"choices\":[{\"delta\":{\"content\":\"Let me\"}}]}\r\r");
            assertThat(relayed).contains("upstream_stalled");
        }

        @Test
        @DisplayName("a Hephaestus call whose provider sends no headers gets a gateway timeout the host can name")
        void aConversationCallWithoutHeadersGetsAGatewayTimeout() throws Exception {
            streamingController = controllerWith(FIRST_BYTE_ONLY);
            ResponseEntity<?> result;
            try (SilentProvider provider = new SilentProvider()) {
                ProxyRouting routing = conversationTurnRouting(provider.baseUrl());
                authenticate(routing);
                stubCredential(
                        routing,
                        new LlmModelResolver.ProxyCredential(
                                provider.baseUrl(),
                                "openai-completions",
                                LlmAuthMode.BEARER,
                                "catalog-model",
                                "secret",
                                42L));

                result = streamingController.proxy(
                        request("POST", "/internal/llm/chat/completions"),
                        new MockHttpServletResponse(),
                        new HttpHeaders(),
                        "{\"stream\":true,\"messages\":[]}".getBytes(StandardCharsets.UTF_8));
            }

            assertThat(Objects.requireNonNull(result).getStatusCode().value()).isEqualTo(504);
            assertThat(OBJECT_MAPPER
                            .readTree(String.valueOf(result.getBody()))
                            .path("error")
                            .path("code")
                            .asString())
                    .isEqualTo("upstream_stalled");
            assertThat(String.valueOf(result.getBody())).contains("no first byte within 1 seconds");
            verify(callLedger)
                    .complete(any(), eq(CALL), any(), eq(new ModelCallFailure(LlmProxyService.UPSTREAM_STALLED, null)));
        }

        @Test
        @DisplayName("a conversation turn records each failed model call by kind and status")
        void aConversationAttemptRecordsEachFailedModelCall() throws Exception {
            ProxyRouting routing = conversationTurnRouting(upstream.url("/v1").toString());
            authenticate(routing);
            stubCredential(
                    routing,
                    new LlmModelResolver.ProxyCredential(
                            upstream.url("/v1").toString(),
                            "openai-completions",
                            LlmAuthMode.BEARER,
                            "catalog-model",
                            "secret",
                            42L));
            upstream.enqueue(new MockResponse.Builder()
                    .code(503)
                    .addHeader("Content-Type", "application/json")
                    .body("{\"error\":{\"message\":\"overloaded\"}}")
                    .build());
            controllerProxy(new MockHttpServletResponse());
            upstream.enqueue(new MockResponse.Builder()
                    .code(503)
                    .addHeader("Content-Type", "application/json")
                    .body("{\"error\":{\"message\":\"overloaded\"}}")
                    .build());
            controllerProxy(new MockHttpServletResponse());
            // The same turn's next call goes silent after its headers, so only the idle bound can end it.
            stalledAfter(routing, "data: {\"choices\":[{\"delta\":{\"content\":\"Let me\"}}]}\n\n");

            ArgumentCaptor<ModelCallFailure> failures = ArgumentCaptor.forClass(ModelCallFailure.class);
            verify(callLedger, times(3)).complete(any(), eq(CALL), any(), failures.capture());
            assertThat(failures.getAllValues())
                    .containsExactly(
                            new ModelCallFailure(LlmProxyService.UPSTREAM_STATUS, 503),
                            new ModelCallFailure(LlmProxyService.UPSTREAM_STATUS, 503),
                            new ModelCallFailure("upstream_idle", 200));
        }

        @Test
        @DisplayName("a call that fails before it reaches the provider leaves nothing in flight for the turn's end")
        void aCallThatFailsBeforeTheProviderLeavesNothingInFlight() {
            ProxyRouting routing = conversationTurnRouting(upstream.url("/v1").toString());
            authenticate(routing);
            Tracer broken = mock(Tracer.class);
            when(broken.spanBuilder()).thenThrow(new IllegalStateException("tracing is down"));
            LlmProxyService service = controllerWith(WebClient.builder().build(), broken);

            assertThatThrownBy(() -> service.proxy(
                            request("POST", "/internal/llm/chat/completions"),
                            new MockHttpServletResponse(),
                            new HttpHeaders(),
                            "{\"stream\":true,\"messages\":[]}".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(IntStream.range(0, ProxyCallsInFlight.TURN_CALLS)
                            .mapToObj(index -> streamingCalls.begin(routing.attempt()))
                            .toList())
                    .noneMatch(ProxyCallsInFlight.Call::isOverCap);
        }

        @Test
        @DisplayName("AI choice refuses a conversation before an upstream or billed call opens")
        void shouldRefuseAiChoiceBeforeOpeningAnUpstreamOrBilledCall() {
            ProxyRouting routing = conversationTurnRouting(upstream.url("/v1").toString());
            authenticate(routing);
            when(requestPolicy.allows(any())).thenReturn(false);

            var answer = Objects.requireNonNull(streamingController.proxy(
                    request("POST", "/internal/llm/chat/completions"),
                    new MockHttpServletResponse(),
                    new HttpHeaders(),
                    "{\"stream\":true,\"messages\":[]}".getBytes(StandardCharsets.UTF_8)));

            assertThat(answer.getStatusCode().value()).isEqualTo(403);
            assertThat(upstream.getRequestCount()).isZero();
            verifyNoInteractions(resolver, budgetGate, callLedger);
        }

        @Test
        @DisplayName("a review's failed model call is recorded in the same ledger")
        void aReviewsFailedModelCallIsRecorded() throws Exception {
            ProxyRouting routing = streamingRouting();
            authenticate(routing);
            stubCredential(
                    routing,
                    new LlmModelResolver.ProxyCredential(
                            upstream.url("/v1").toString(),
                            "openai-completions",
                            LlmAuthMode.BEARER,
                            "catalog-model",
                            "secret",
                            42L));
            upstream.enqueue(new MockResponse.Builder()
                    .code(503)
                    .addHeader("Content-Type", "application/json")
                    .body("{\"error\":{\"message\":\"overloaded\"}}")
                    .build());

            controllerProxy(new MockHttpServletResponse());

            verify(callLedger)
                    .complete(any(), eq(CALL), any(), eq(new ModelCallFailure(LlmProxyService.UPSTREAM_STATUS, 503)));
        }

        @Test
        @DisplayName("a connection whose breaker is open refuses the call with a pause the host can name")
        void anOpenBreakerRefusesTheCallBeforeTheProvider() throws Exception {
            upstream.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body("data: [DONE]\n\n")
                    .build());
            ProxyRouting routing = streamingRouting();
            for (int failure = 0; failure < 3; failure++) {
                Objects.requireNonNull(streamingHealth.acquire(routing.connectionScope(), routing.connectionId()))
                        .failed(new IllegalStateException("upstream stalled"));
            }
            authenticate(routing);
            stubCredential(
                    routing,
                    new LlmModelResolver.ProxyCredential(
                            upstream.url("/v1").toString(),
                            "openai-completions",
                            LlmAuthMode.BEARER,
                            "catalog-model",
                            "secret",
                            42L));

            ResponseEntity<?> result = streamingController.proxy(
                    request("POST", "/internal/llm/chat/completions"),
                    new MockHttpServletResponse(),
                    new HttpHeaders(),
                    "{\"stream\":true,\"messages\":[]}".getBytes(StandardCharsets.UTF_8));

            assertThat(Objects.requireNonNull(result).getStatusCode().value()).isEqualTo(503);
            assertThat(OBJECT_MAPPER
                            .readTree(String.valueOf(result.getBody()))
                            .path("error")
                            .path("code")
                            .asString())
                    .isEqualTo("upstream_paused");
            assertThat(upstream.getRequestCount()).isZero();
        }

        @Test
        @DisplayName("provider server errors open the connection's breaker; client errors do not")
        void serverErrorsOpenTheBreakerAndClientErrorsDoNot() throws Exception {
            ProxyRouting routing = streamingRouting();
            authenticate(routing);
            stubCredential(
                    routing,
                    new LlmModelResolver.ProxyCredential(
                            upstream.url("/v1").toString(),
                            "openai-completions",
                            LlmAuthMode.BEARER,
                            "catalog-model",
                            "secret",
                            42L));
            for (int call = 0; call < 3; call++) {
                upstream.enqueue(new MockResponse.Builder()
                        .code(400)
                        .addHeader("Content-Type", "application/json")
                        .body("{\"error\":{\"message\":\"bad request\"}}")
                        .build());
                controllerProxy(new MockHttpServletResponse());
            }
            assertThat(streamingHealth.isOpen(routing.connectionScope(), routing.connectionId()))
                    .isFalse();

            for (int call = 0; call < 3; call++) {
                upstream.enqueue(new MockResponse.Builder()
                        .code(500)
                        .addHeader("Content-Type", "application/json")
                        .body("{\"error\":{\"message\":\"overloaded\"}}")
                        .build());
                controllerProxy(new MockHttpServletResponse());
            }
            assertThat(streamingHealth.isOpen(routing.connectionScope(), routing.connectionId()))
                    .isTrue();
        }

        @Test
        @DisplayName(
                "a call past its attempt's cap of calls in flight is refused before the provider and may ask again")
        void shouldRefuseTheCallWhenItsAttemptHasItsMostCallsInFlight() {
            ProxyRouting routing = streamingRouting();
            authenticate(routing);
            IntStream.range(0, REVIEW_CALLS_AT_THREE).forEach(index -> streamingCalls.begin(ATTEMPT));

            ResponseEntity<?> answer = streamingController.proxy(
                    request("POST", "/internal/llm/chat/completions"),
                    new MockHttpServletResponse(),
                    new HttpHeaders(),
                    "{\"stream\":true,\"messages\":[]}".getBytes(StandardCharsets.UTF_8));

            assertThat(answer).isNotNull();
            assertThat(answer.getStatusCode().value()).isEqualTo(429);
            assertThat(answer.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(answer.getHeaders().getFirst(LlmProxyService.REFUSAL_HEADER))
                    .isEqualTo("CALLS_IN_FLIGHT");
            assertThat(upstream.getRequestCount()).isZero();
            assertThat(streamingMeterRegistry
                            .counter(AgentMetrics.LLM_PROXY_REFUSED, "refusal", "calls_in_flight")
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("a call that the provider fails frees its place in the attempt's cap")
        void shouldFreeThePlaceWhenTheProviderFailsTheCall() {
            upstream.enqueue(new MockResponse.Builder().code(500).body("down").build());
            ProxyRouting routing = streamingRouting();
            authenticate(routing);
            stubCredential(
                    routing,
                    new LlmModelResolver.ProxyCredential(
                            upstream.url("/v1").toString(),
                            "openai-completions",
                            LlmAuthMode.BEARER,
                            "catalog-model",
                            "secret",
                            42L));

            controllerProxy(new MockHttpServletResponse());

            assertThat(IntStream.range(0, REVIEW_CALLS_AT_THREE)
                            .mapToObj(index -> streamingCalls.begin(ATTEMPT))
                            .toList())
                    .as("the whole cap is free again")
                    .noneMatch(ProxyCallsInFlight.Call::isOverCap);
        }

        @Test
        @DisplayName("a call that cannot reach the provider frees its place in the attempt's cap")
        void shouldFreeThePlaceWhenTheProviderCannotBeReached() throws IOException {
            String closed = upstream.url("/v1").toString();
            upstream.close();
            ProxyRouting routing = streamingRouting();
            authenticate(routing);
            stubCredential(
                    routing,
                    new LlmModelResolver.ProxyCredential(
                            closed, "openai-completions", LlmAuthMode.BEARER, "catalog-model", "secret", 42L));

            controllerProxy(new MockHttpServletResponse());
        }

        @Test
        @DisplayName("a call that begins after its attempt ended never reaches the provider")
        void aCallThatBeginsAfterItsAttemptEndedNeverReachesTheProvider() throws Exception {
            CountDownLatch authenticated = new CountDownLatch(1);
            CountDownLatch ended = new CountDownLatch(1);
            AtomicBoolean held = new AtomicBoolean(true);
            when(routingService.ownsAttempt(any())).thenAnswer(ignored -> held.get());
            // The pause between authentication and registration, where a stop can end the attempt.
            ProxyCallsInFlight calls = new ProxyCallsInFlight(12) {
                @Override
                public Call begin(ProxyRouting.BilledAttempt billed) {
                    authenticated.countDown();
                    try {
                        ended.await(5, TimeUnit.SECONDS);
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
                    new ProxyAccounting(budgetGate, callLedger, streamingMeterRegistry, OBJECT_MAPPER),
                    requestPolicy,
                    mock(ScanCallGuard.class),
                    routingService,
                    calls,
                    new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults()),
                    admittingPermits(),
                    SILENCE_NEVER_REACHED);
            upstream.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body("data: [DONE]\n\n")
                    .build());
            ProxyRouting routing = streamingRouting();
            authenticate(routing);
            lenient()
                    .when(resolver.resolveProxyCredential(eq(new LlmModelResolver.ConnectionRef(
                            routing.connectionScope(),
                            routing.connectionId(),
                            routing.modelId(),
                            routing.workspaceId()))))
                    .thenReturn(new LlmModelResolver.ProxyCredential(
                            upstream.url("/v1").toString(),
                            "openai-completions",
                            LlmAuthMode.BEARER,
                            "catalog-model",
                            "secret",
                            42L));
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

            held.set(false);
            ended.countDown();

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
            ArgumentCaptor<LlmUsageRecorder.CallUsage> usage =
                    ArgumentCaptor.forClass(LlmUsageRecorder.CallUsage.class);
            verify(callLedger).complete(any(), eq(CALL), usage.capture(), any());
            assertThat(usage.getValue().inputTokens()).isEqualTo(75);
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
            verify(callLedger).complete(any(), eq(CALL), isNull(), any());
            assertThat(missingUsage()).isEqualTo(1.0);
        }

        @Test
        void malformedStreamUsageIsCounted() throws Exception {
            proxyStream("{\"usage\":{\"prompt_tokens\":1,\"prompt_tokens_details\":{\"cached_tokens\":2}}}");

            assertThat(streamingMeterRegistry
                            .counter("llm.proxy.usage.unparseable", "sourceType", UsageSourceType.REVIEW_JOB.name())
                            .count())
                    .isEqualTo(1.0);
            assertThat(missingUsage())
                    .as("malformed usage is not also counted as absent")
                    .isZero();
            verify(callLedger).complete(any(), eq(CALL), isNull(), any());
        }

        private double missingUsage() {
            return streamingMeterRegistry
                    .counter("llm.proxy.usage.missing", "sourceType", UsageSourceType.REVIEW_JOB.name())
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
        void shouldCompleteTheCallWithARefusalClassifiedBeforeTheProxyReturns() {
            upstream.enqueue(new MockResponse.Builder()
                    .code(400)
                    .addHeader("Content-Type", "application/json")
                    .body("{\"error\":{\"message\":\"Unrecognized request argument: stream_options\"}}")
                    .build());
            ProxyRouting routing = streamingRouting();
            stubStreamCredential(routing);
            when(requestPolicy.allows(any())).thenReturn(true, true, false);

            var answer = proxyReview(routing, new MockHttpServletResponse());

            assertThat(answer).isNotNull();
            assertThat(answer.getStatusCode().value()).isEqualTo(403);
            assertThat(upstream.getRequestCount()).isEqualTo(1);
            verify(callLedger)
                    .complete(
                            any(),
                            eq(CALL),
                            eq(LlmUsageRecorder.CallUsage.NONE),
                            eq(new ModelCallFailure("refused_ai_choice", 403)));
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
                            "secret",
                            42L));
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
                    UsageFundingSource.INSTANCE,
                    7L,
                    8L,
                    9L,
                    ATTEMPT,
                    laterThanAnyTest(),
                    4096L);
            authenticate(routing);
            stubCredential(
                    routing,
                    new LlmModelResolver.ProxyCredential(
                            upstream.url("/v1").toString(),
                            "openai-responses",
                            LlmAuthMode.BEARER,
                            "catalog-model",
                            "secret",
                            42L));
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
            ArgumentCaptor<LlmUsageRecorder.CallUsage> usage =
                    ArgumentCaptor.forClass(LlmUsageRecorder.CallUsage.class);
            verify(callLedger).complete(any(), eq(CALL), usage.capture(), any());
            assertThat(usage.getValue().inputTokens()).isEqualTo(75);
            assertThat(usage.getValue().outputTokens()).isEqualTo(40);
            assertThat(usage.getValue().reasoningTokens()).isEqualTo(30);
            assertThat(usage.getValue().cacheReadTokens()).isEqualTo(25);
        }

        // --- The attempt's work deadline -------------------------------------------------------------

        private static final String USAGE_FRAME =
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":40}}\n\n";
        private static final byte[] STREAM_REQUEST =
                "{\"stream\":true,\"messages\":[]}".getBytes(StandardCharsets.UTF_8);

        private void stubStreamCredential(ProxyRouting routing) {
            var credential = new LlmModelResolver.ProxyCredential(
                    upstream.url("/v1").toString(),
                    "openai-completions",
                    LlmAuthMode.BEARER,
                    "catalog-model",
                    "secret",
                    42L);
            if (routing.attempt().sourceType() == UsageSourceType.PRODUCT_FEEDBACK_DRAFT) {
                var ref = new LlmModelResolver.ConnectionRef(
                        routing.connectionScope(), routing.connectionId(), routing.modelId(), null);
                when(resolver.resolveInstanceModel(Objects.requireNonNull(routing.modelId())))
                        .thenReturn(new LlmModelResolver.InstanceModel(
                                ref, credential, LlmApiProtocol.OPENAI_COMPLETIONS, null));
            } else {
                stubCredential(routing, credential);
            }
        }

        private @Nullable ResponseEntity<?> proxyReview(ProxyRouting routing, HttpServletResponse response) {
            authenticate(routing);
            return streamingController.proxy(
                    request("POST", "/internal/llm/chat/completions"), response, new HttpHeaders(), STREAM_REQUEST);
        }

        private void assertBilledOnceForTheUsageFrame() {
            ArgumentCaptor<LlmUsageRecorder.CallUsage> usage =
                    ArgumentCaptor.forClass(LlmUsageRecorder.CallUsage.class);
            verify(callLedger).complete(any(), eq(CALL), usage.capture(), any());
            assertThat(usage.getValue().inputTokens()).isEqualTo(100);
            assertThat(usage.getValue().outputTokens()).isEqualTo(40);
            assertThat(missingUsage()).isZero();
        }

        private double errors() {
            return streamingMeterRegistry
                    .counter(AgentMetrics.LLM_PROXY_ERRORS, "apiProtocol", "openai-completions")
                    .count();
        }

        @Test
        @DisplayName(
                "a call after the attempt's work time makes no request and is refused with words no client retries")
        void aCallAfterTheWorkTimeMakesNoRequest() {
            ProxyRouting routing = streamingRouting(Instant.now().minusSeconds(1));
            MockHttpServletResponse response = new MockHttpServletResponse();

            var result = proxyReview(routing, response);

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode().value()).isEqualTo(403);
            assertThat(result.getBody()).isEqualTo(LlmProxyService.WORK_TIME_OVER);
            assertThat(LlmProxyService.WORK_TIME_OVER.toLowerCase(Locale.ROOT))
                    .doesNotContain("timeout", "timed out", "overload", "rate limit", "try again");
            assertThat(upstream.getRequestCount()).isZero();
            verify(resolver, never()).resolveProxyCredential(any());
            verifyNoInteractions(callLedger);
            assertThat(response.isCommitted()).isFalse();
        }

        @Test
        @DisplayName("a deadline that passes before the headers writes nothing, bills nothing, and is refused")
        void aDeadlineBeforeTheHeadersWritesNothing() throws Exception {
            Semaphore requested = new Semaphore(0);
            try (VirtualCall call = new VirtualCall(answeringInTurn(requested, Mono.never()), SILENCE_NEVER_REACHED)) {
                call.start(streamingRouting(call.now().plusSeconds(1)));
                assertThat(requested.tryAcquire(10, TimeUnit.SECONDS)).isTrue();
                call.time.advanceTimeBy(Duration.ofSeconds(1));

                var result = call.finished();

                assertThat(result).isNotNull();
                assertThat(result.getStatusCode().value()).isEqualTo(403);
                assertThat(result.getBody()).isEqualTo(LlmProxyService.WORK_TIME_OVER);
                // Nothing reached the client: no status, header or byte.
                assertThat(call.response.isCommitted()).isFalse();
                assertThat(call.response.getContentAsByteArray()).isEmpty();
                assertThat(call.response.getHeader("X-Accel-Buffering")).isNull();
                verify(callLedger).complete(any(), eq(CALL), isNull(), any());
                assertThat(missingUsage()).isZero();
                assertThat(requested.availablePermits()).isZero();
            }
        }

        @Test
        void aDraftThatReachesItsDeadlineEndsTheStreamWithAnError() throws Exception {
            String partial = "data: {\"choices\":[{\"delta\":{\"content\":\"Let me\"}}]}\n\n";
            try (VirtualCall call = new VirtualCall(
                    streaming(Flux.just(sse(partial)).concatWith(Flux.never())), SILENCE_NEVER_REACHED)) {
                Duration workTime = Duration.ofSeconds(1);
                var routing = new ProxyRouting(
                        "report-draft",
                        "openai-completions",
                        upstream.url("/v1").toString(),
                        UsageFundingSource.INSTANCE,
                        7L,
                        8L,
                        null,
                        new ProxyRouting.BilledAttempt(
                                UsageSourceType.PRODUCT_FEEDBACK_DRAFT, UUID.randomUUID(), 1, LEASE, null, 1),
                        call.now().plus(workTime),
                        100L);
                call.start(routing);
                call.relayed();
                call.time.advanceTimeBy(workTime);
                assertThat(call.finished()).isNull();
                assertThat(call.response.getContentAsString())
                        .startsWith(partial)
                        .contains("draft_deadline:");
            }
        }

        @Test
        @DisplayName("the retry without stream_options gets what is left of the deadline, not a new window")
        void theUsageRetryGetsWhatIsLeftOfTheDeadline() throws Exception {
            Semaphore requested = new Semaphore(0);
            Sinks.One<ClientResponse> refusal = Sinks.one();
            try (VirtualCall call = new VirtualCall(
                    answeringInTurn(requested, refusal.asMono(), Mono.never()), SILENCE_NEVER_REACHED)) {
                call.start(streamingRouting(call.now().plusSeconds(2)));
                assertThat(requested.tryAcquire(10, TimeUnit.SECONDS)).isTrue();
                call.time.advanceTimeBy(Duration.ofSeconds(1));
                refusal.emitValue(
                        ClientResponse.create(HttpStatusCode.valueOf(400))
                                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                                .body("{\"error\":{\"message\":\"Unrecognized request argument: stream_options\"}}")
                                .build(),
                        Sinks.EmitFailureHandler.FAIL_FAST);
                assertThat(requested.tryAcquire(10, TimeUnit.SECONDS)).isTrue();
                // The deadline is now 1 s away; a new window would keep the retry open for 2 s.
                call.time.advanceTimeBy(Duration.ofSeconds(1));

                var result = call.finished();

                assertThat(result).isNotNull();
                assertThat(result.getStatusCode().value()).isEqualTo(403);
                assertThat(requested.availablePermits()).isZero();
                assertThat(call.response.isCommitted()).isFalse();
                verify(callLedger).complete(any(), eq(CALL), isNull(), any());
            }
        }

        /** One SSE answer to every request, counting them; its body is the given one. */
        private WebClient answering(AtomicInteger requests, Flux<DataBuffer> body) {
            var answer = ClientResponse.create(HttpStatusCode.valueOf(200))
                    .header(HttpHeaders.CONTENT_TYPE, "text/event-stream")
                    .body(body)
                    .build();
            return WebClient.builder()
                    .exchangeFunction(request -> {
                        requests.incrementAndGet();
                        return Mono.just(answer);
                    })
                    .build();
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void shouldKeepAnthropicOpeningUsageUnmeasuredWhenTheStreamEndsBeforeFinalUsage(boolean fails)
                throws Exception {
            String opening =
                    "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":100,\"output_tokens\":1}}}\n\n";
            Flux<DataBuffer> body =
                    Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(opening.getBytes(StandardCharsets.UTF_8)));
            if (fails) body = body.concatWith(Flux.error(new IOException("connection reset")));
            var service = controllerWith(answering(new AtomicInteger(), body));
            ProxyRouting route = routing("anthropic-messages");
            stubCredential(
                    route,
                    new LlmModelResolver.ProxyCredential(
                            upstream.url("/v1").toString(),
                            "anthropic-messages",
                            LlmAuthMode.API_KEY,
                            "catalog-model",
                            "secret",
                            42L));
            authenticate(route);
            MockHttpServletResponse response = new MockHttpServletResponse();
            service.proxy(request("POST", "/internal/llm/v1/messages"), response, new HttpHeaders(), STREAM_REQUEST);
            assertThat(response.getContentAsString()).isEqualTo(opening);
            verify(callLedger).complete(any(), eq(CALL), isNull(), any());
        }

        private static DataBuffer usageFrame() {
            return DefaultDataBufferFactory.sharedInstance.wrap(USAGE_FRAME.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * The write is held past the deadline and opened only by another thread once the upstream is cancelled, so
         * the cancel cannot have waited for the write; the request thread makes every response call.
         */
        @Test
        @DisplayName("the deadline cancels the upstream during a held client write; the request thread settles it once")
        void theDeadlineCancelsTheUpstreamDuringAHeldWrite() throws Exception {
            var cancelled = new CountDownLatch(1);
            var requests = new AtomicInteger();
            var service = controllerWith(answering(
                    requests, Flux.just(usageFrame()).concatWith(Flux.never()).doOnCancel(cancelled::countDown)));
            ProxyRouting routing = streamingRouting(Instant.now().plusSeconds(1));
            stubStreamCredential(routing);
            authenticate(routing);
            var callers = new ConcurrentLinkedQueue<Thread>();
            var writing = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            ServletOutputStream output = mock(ServletOutputStream.class, call -> {
                callers.add(Thread.currentThread());
                if (call.getMethod().getName().equals("write")) {
                    writing.countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                }
                return RETURNS_DEFAULTS.answer(call);
            });
            HttpServletResponse response = mock(HttpServletResponse.class, call -> {
                callers.add(Thread.currentThread());
                return call.getMethod().getName().equals("getOutputStream") ? output : RETURNS_DEFAULTS.answer(call);
            });
            var cancelledWhileHeld = new AtomicBoolean();
            var releaser = new Thread(() -> {
                try {
                    cancelledWhileHeld.set(
                            writing.await(10, TimeUnit.SECONDS) && cancelled.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    release.countDown();
                }
            });
            releaser.start();

            var result = service.proxy(
                    request("POST", "/internal/llm/chat/completions"), response, new HttpHeaders(), STREAM_REQUEST);
            releaser.join(10_000);

            assertThat(cancelledWhileHeld).isTrue();
            assertThat(result).isNull();
            assertThat(callers).isNotEmpty().containsOnly(Thread.currentThread());
            assertBilledOnceForTheUsageFrame();
            verify(response).setStatus(200);
            verify(response, never()).setStatus(403);
            verify(response, never()).setStatus(502);
            verify(response, never()).sendError(anyInt());
            assertThat(errors()).isEqualTo(1.0);
            assertThat(requests).hasValue(1);
        }

        @Test
        @DisplayName("a stream that fails after its usage frame keeps its committed response and is billed once")
        void aStreamThatFailsAfterItsUsageFrameIsBilledOnce() throws Exception {
            var requests = new AtomicInteger();
            var service = controllerWith(answering(
                    requests, Flux.just(usageFrame()).concatWith(Flux.error(new IOException("connection reset")))));
            ProxyRouting routing = streamingRouting();
            stubStreamCredential(routing);
            authenticate(routing);
            MockHttpServletResponse response = new MockHttpServletResponse();

            var result = service.proxy(
                    request("POST", "/internal/llm/chat/completions"), response, new HttpHeaders(), STREAM_REQUEST);

            assertThat(result).isNull();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEqualTo(USAGE_FRAME);
            assertBilledOnceForTheUsageFrame();
            assertThat(errors()).isEqualTo(1.0);
            assertThat(requests).hasValue(1);
        }

        @Test
        @DisplayName("the production client keeps its own timeouts and ends a stalled stream at the work deadline")
        void theConfiguredClientEndsAStalledStreamAtTheWorkDeadline() throws Exception {
            var config = new LlmProxyWebClientConfig();
            var pool = config.llmProxyConnectionProvider();
            var loops = config.llmProxyLoopResources();
            var streams = new AtomicInteger();
            var received = new AtomicReference<@Nullable Instant>();
            var cancelled = new CountDownLatch(1);
            // The usage frame, flushed on its own, then nothing: the stream ends only when the client cancels it.
            Flux<Publisher<? extends ByteBuf>> groups = Flux.concat(
                    Mono.just(Mono.fromSupplier(() -> Unpooled.copiedBuffer(USAGE_FRAME, StandardCharsets.UTF_8))),
                    Flux.never());
            var server = HttpServer.create()
                    .host("127.0.0.1")
                    .port(0)
                    .handle((request, response) -> {
                        if (request.uri().startsWith("/warm"))
                            return Mono.from(response.status(204).send());
                        streams.incrementAndGet();
                        received.compareAndSet(null, Instant.now());
                        return request.receive()
                                .then()
                                .then(Mono.from(response.header(HttpHeaders.CONTENT_TYPE, "text/event-stream")
                                        .sendGroups(groups.doOnCancel(cancelled::countDown))));
                    })
                    .bindNow(Duration.ofSeconds(10));
            try {
                String base = "http://127.0.0.1:" + server.port();
                WebClient configured = config.llmProxyWebClient(
                        pool,
                        loops,
                        new EgressPolicy(
                                mock(InstanceLlmSettingsRepository.class),
                                new LlmProperties(
                                        "",
                                        new LlmProperties.Egress(true, List.of(), false),
                                        new LlmProperties.Fx(LlmProperties.ECB_DAILY_URL))),
                        WebClient.builder());
                // The client's first exchange also starts its event loop and opens its pooled connection. That
                // one-time start is no part of a work deadline, so it happens before the deadline is set.
                configured
                        .get()
                        .uri(base + "/warm")
                        .retrieve()
                        .toBodilessEntity()
                        .block(Duration.ofSeconds(10));
                var service = controllerWith(configured, Tracer.NOOP, SILENCE_NEVER_REACHED);
                Instant deadline = Instant.now().plusSeconds(1);
                ProxyRouting routing = streamingRouting(deadline);
                stubCredential(
                        routing,
                        new LlmModelResolver.ProxyCredential(
                                base + "/v1",
                                "openai-completions",
                                LlmAuthMode.BEARER,
                                "catalog-model",
                                "secret",
                                42L));
                authenticate(routing);
                MockHttpServletResponse response = new MockHttpServletResponse();

                var result = service.proxy(
                        request("POST", "/internal/llm/chat/completions"), response, new HttpHeaders(), STREAM_REQUEST);

                assertThat(result)
                        .as("deadline %s; the upstream received the request at %s", deadline, received.get())
                        .isNull();
                assertThat(Instant.now()).isBefore(deadline.plusSeconds(5));
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(response.getContentAsString()).isEqualTo(USAGE_FRAME);
                assertBilledOnceForTheUsageFrame();
                assertThat(errors()).isEqualTo(1.0);
                assertThat(streams).hasValue(1);
                // The upstream itself sees the stream cancelled.
                assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                server.disposeNow(Duration.ofSeconds(10));
                loops.dispose();
                pool.dispose();
            }
        }

        @ParameterizedTest(name = "conversation={0}")
        @ValueSource(booleans = {false, true})
        @DisplayName(
                "the actual service lets a review stream cross 600 seconds while a conversation keeps its body limit")
        void shouldApplyTheBodyLimitOnlyToTheConversationStream(boolean conversation) throws Exception {
            var executor = Executors.newSingleThreadExecutor();
            // Created before virtual time: the blocking MVC request must have a real owning thread.
            var requestScheduler = Schedulers.fromExecutorService(executor);
            var firstWrite = new CountDownLatch(1);
            var tailArmed = new CountDownLatch(1);
            var requests = new AtomicInteger();
            var span = mock(Span.class);
            var spanBuilder = mock(Span.Builder.class, RETURNS_SELF);
            when(spanBuilder.start()).thenReturn(span);
            var tracer = mock(Tracer.class);
            when(tracer.spanBuilder()).thenReturn(spanBuilder);
            String lateFrame = "data: {\"choices\":[{\"delta\":{\"content\":\"after 600 seconds\"}}]}\n\n";
            var response = new MockHttpServletResponse();
            var delegate = response.getOutputStream();
            ServletOutputStream output = mock(ServletOutputStream.class, call -> {
                if (call.getMethod().getName().equals("write")) {
                    delegate.write(call.getArgument(0, byte[].class));
                    firstWrite.countDown();
                } else if (call.getMethod().getName().equals("flush")) {
                    delegate.flush();
                }
                return RETURNS_DEFAULTS.answer(call);
            });
            var servlet = new HttpServletResponseWrapper(response) {
                @Override
                public ServletOutputStream getOutputStream() {
                    return output;
                }
            };
            var billed = conversation
                    ? new ProxyRouting.BilledAttempt(UsageSourceType.CONVERSATION_TURN, UUID.randomUUID(), 0, null)
                    : ATTEMPT;
            var routing = new ProxyRouting(
                    conversation ? "conversation-session:body-limit" : "job:body-limit",
                    "openai-completions",
                    upstream.url("/v1").toString(),
                    UsageFundingSource.INSTANCE,
                    7L,
                    8L,
                    9L,
                    billed,
                    conversation ? null : Instant.now().plus(Duration.ofHours(3)),
                    4096L);
            stubStreamCredential(routing);
            try {
                StepVerifier.withVirtualTime(() -> Mono.fromCallable(() -> {
                                    var bodyConsumed = new AtomicBoolean();
                                    // An HTTP body is consumed once; response cleanup must not replay it.
                                    Flux<DataBuffer> body = Flux.defer(() -> {
                                        if (!bodyConsumed.compareAndSet(false, true)) return Flux.empty();
                                        return Flux.create(sink -> {
                                            var tail = Schedulers.parallel()
                                                    .schedule(
                                                            () -> {
                                                                sink.next(DefaultDataBufferFactory.sharedInstance.wrap(
                                                                        lateFrame.getBytes(StandardCharsets.UTF_8)));
                                                                sink.complete();
                                                            },
                                                            601,
                                                            TimeUnit.SECONDS);
                                            sink.onDispose(tail);
                                            sink.next(usageFrame());
                                            // The late frame's task must exist before virtual time can advance.
                                            tailArmed.countDown();
                                        });
                                    });
                                    // The review's stream is silent for 600 seconds, so only the conversation keeps its
                                    // idle
                                    // bound.
                                    var service = controllerWith(
                                            answering(requests, body),
                                            tracer,
                                            conversation ? IDLE_ONLY : SILENCE_NEVER_REACHED);
                                    authenticate(routing);
                                    try {
                                        assertThat(service.proxy(
                                                        request("POST", "/internal/llm/chat/completions"),
                                                        servlet,
                                                        new HttpHeaders(),
                                                        STREAM_REQUEST))
                                                .isNull();
                                        return response.getContentAsString();
                                    } finally {
                                        SecurityContextHolder.clearContext();
                                    }
                                })
                                .subscribeOn(requestScheduler))
                        .then(() -> {
                            try {
                                assertThat(tailArmed.await(5, TimeUnit.SECONDS)).isTrue();
                                assertThat(firstWrite.await(5, TimeUnit.SECONDS))
                                        .isTrue();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(interrupted);
                            }
                        })
                        .thenAwait(Duration.ofSeconds(601))
                        // A conversation turn's stream ends at its idle bound, long before the body limit, with the
                        // stall
                        // event.
                        .expectNextMatches(content -> conversation
                                ? content.startsWith(USAGE_FRAME)
                                        && content.contains(LlmProxyService.UPSTREAM_STALLED)
                                        && !content.contains("after 600 seconds")
                                : content.equals(USAGE_FRAME + lateFrame))
                        .expectComplete()
                        .verify(Duration.ofSeconds(10));
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(response.isCommitted()).isTrue();
                assertThat(requests).hasValue(1);
                assertThat(errors()).isEqualTo(conversation ? 1.0 : 0.0);
                verify(span).tag("hephaestus.stream.outcome", conversation ? "UPSTREAM_IDLE" : "COMPLETED");
                ArgumentCaptor<LlmUsageRecorder.CallUsage> usage =
                        ArgumentCaptor.forClass(LlmUsageRecorder.CallUsage.class);
                verify(callLedger).complete(any(), eq(CALL), usage.capture(), any());
                assertThat(usage.getValue().inputTokens()).isEqualTo(100);
                assertThat(usage.getValue().outputTokens()).isEqualTo(40);
                assertThat(streamingMeterRegistry
                                .counter(
                                        "llm.proxy.usage.missing",
                                        "sourceType",
                                        billed.sourceType().name())
                                .count())
                        .isZero();
            } finally {
                requestScheduler.dispose();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    @DisplayName("a review stream may wait all of its remaining work time; other job calls keep the proxy ceiling")
    void shouldGiveAReviewStreamItsRemainingWorkTimeWithoutTheOldStreamCap() {
        Duration ceiling = Duration.ofSeconds(910);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Instant longJob = now.plus(Duration.ofHours(3));

        assertThat(LlmProxyService.exchangeWait(longJob, true, now)).isEqualTo(Duration.ofHours(3));
        assertThat(LlmProxyService.exchangeWait(longJob, false, now)).isEqualTo(ceiling);
        assertThat(LlmProxyService.exchangeWait(now.plusSeconds(10), false, now))
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(LlmProxyService.exchangeWait(now.minusSeconds(1), true, now)).isZero();
    }

    @Test
    @DisplayName("a conversation call outlasts its turn's deadline stop, so the turn ends as a deadline")
    void shouldBoundAConversationCallPastTheTurnCeilingAndItsStopGrace() {
        Duration turnCeiling = Duration.ofSeconds(ModelAssignmentLimits.MAX_CONVERSATION_TURN_SECONDS);
        // The dispatcher's tick (5 s) and the host's stop grace (30 s) end the turn before the call's own bound.
        Duration stopped = turnCeiling.plusSeconds(5).plusSeconds(30);

        assertThat(LlmProxyService.exchangeWait(null, true, Instant.EPOCH)).isGreaterThan(stopped);
        assertThat(LlmProxyService.exchangeWait(null, false, Instant.EPOCH)).isGreaterThan(stopped);
    }

    @Nested
    class HeaderAllowlist {
        @Test
        void shouldInjectNativeAnthropicHeadersWithoutForwardingCallerKeysOrBetas() {
            var incoming = incomingHeaders();
            incoming.set("anthropic-version", "caller-version");
            incoming.set("anthropic-beta", "caller-beta");
            var output = LlmProxyService.buildUpstreamHeaders(
                    incoming, credential("anthropic-messages", LlmAuthMode.API_KEY));
            assertThat(output.getFirst("x-api-key")).isEqualTo("secret");
            assertThat(output.getFirst("anthropic-version")).isEqualTo("2023-06-01");
            assertThat(output.get("anthropic-beta")).isNull();
            assertThat(output.get(HttpHeaders.AUTHORIZATION)).isNull();
            assertThat(output.get("api-key")).isNull();
        }

        @Test
        void shouldInjectBearerAuthAndDropUnapprovedHeaders() {
            var incoming = incomingHeaders();

            HttpHeaders output = LlmProxyService.buildUpstreamHeaders(
                    incoming, credential("openai-completions", LlmAuthMode.BEARER));

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
            HttpHeaders output = LlmProxyService.buildUpstreamHeaders(
                    incomingHeaders(), credential("openai-responses", LlmAuthMode.API_KEY));

            assertThat(output.getFirst("api-key")).isEqualTo("secret");
            assertThat(output.get(HttpHeaders.AUTHORIZATION)).isNull();
        }

        @Test
        void shouldNotInjectAuthWhenKeyIsBlank() {
            var credential = new LlmModelResolver.ProxyCredential(
                    "https://api.example.com/v1", "openai-completions", LlmAuthMode.BEARER, "catalog-model", " ", 42L);

            HttpHeaders output = LlmProxyService.buildUpstreamHeaders(incomingHeaders(), credential);

            assertThat(output.get(HttpHeaders.AUTHORIZATION)).isNull();
            assertThat(output.get("api-key")).isNull();
        }
    }

    @Test
    void shouldBuildCanonicalProtocolUrls() {
        assertThat(LlmProxyService.buildUpstreamUri("https://api.example.com/v1/", LlmApiProtocol.OPENAI_COMPLETIONS))
                .isEqualTo(URI.create("https://api.example.com/v1/chat/completions"));
        assertThat(LlmProxyService.buildUpstreamUri("https://api.example.com/v1", LlmApiProtocol.OPENAI_RESPONSES))
                .isEqualTo(URI.create("https://api.example.com/v1/responses"));
    }

    private static byte[] jsonBody() {
        return "{\"model\":\"anything\",\"stream\":true}".getBytes(StandardCharsets.UTF_8);
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

    private double blocked(String cap) {
        return meterRegistry
                .counter(AgentMetrics.LLM_BUDGET_BLOCKED, "surface", "model_call", "cap", cap)
                .count();
    }

    // How long a conversation turn's or a review stream's provider may take to its first byte in these tests.
    private static final Duration CONVERSATION_FIRST_BYTE = Duration.ofMillis(1500);

    // How long a conversation turn's stream, or a review's stream, may send no byte in these tests.
    private static final Duration CONVERSATION_IDLE = Duration.ofMillis(400);

    // Silence bounds that no test reaches: under any load, only the bound a test is about can end its call.
    private static final ConversationCallProperties SILENCE_NEVER_REACHED =
            new ConversationCallProperties(Duration.ofHours(1), Duration.ofHours(1));

    private static final ConversationCallProperties IDLE_ONLY =
            new ConversationCallProperties(Duration.ofHours(1), CONVERSATION_IDLE);

    private static final ConversationCallProperties FIRST_BYTE_ONLY =
            new ConversationCallProperties(CONVERSATION_FIRST_BYTE, Duration.ofHours(1));

    // The review cap at practice concurrency 3: 2 × (3 + 1) + 4.
    private static final int REVIEW_CALLS_AT_THREE = 12;

    private static final ProxyRouting.BilledAttempt ATTEMPT = new ProxyRouting.BilledAttempt(
            UsageSourceType.REVIEW_JOB, UUID.fromString("00000000-0000-0000-0000-0000000000aa"), 0, OTHER_LEASE);

    // A work deadline no test reaches, for job routes whose test is not about it.
    private static Instant laterThanAnyTest() {
        return Instant.now().plus(Duration.ofHours(3));
    }

    private static ProxyRouting routing(String protocol) {
        return new ProxyRouting(
                "job:test",
                protocol,
                "https://frozen.example.com/v1",
                UsageFundingSource.INSTANCE,
                7L,
                8L,
                9L,
                ATTEMPT,
                laterThanAnyTest(),
                4096L);
    }

    private static ProxyRouting workspaceFundedRouting() {
        return new ProxyRouting(
                "job:test",
                "openai-completions",
                "https://frozen.example.com/v1",
                UsageFundingSource.WORKSPACE,
                7L,
                8L,
                9L,
                ATTEMPT,
                laterThanAnyTest(),
                4096L);
    }

    private static ProxyRouting withOutputLimit(ProxyRouting routing, @Nullable Long outputLimit) {
        return new ProxyRouting(
                routing.principalDescription(),
                routing.apiProtocol(),
                routing.baseUrl(),
                routing.connectionScope(),
                routing.connectionId(),
                routing.modelId(),
                routing.workspaceId(),
                routing.attempt(),
                routing.workDeadline(),
                outputLimit);
    }

    private static LlmModelResolver.ProxyCredential credential(String protocol, LlmAuthMode authMode) {
        return new LlmModelResolver.ProxyCredential(
                "https://api.example.com/v1", protocol, authMode, "catalog-model", "secret", 42L);
    }

    private void authenticate(ProxyRouting routing) {
        Instant now = Instant.now();
        var job = new AttemptJwt(
                AttemptJwt.Kind.REVIEW,
                routing.sourceId(),
                9L,
                1,
                UUID.randomUUID(),
                Set.of(),
                "test",
                now,
                now.plusSeconds(60),
                now.plusSeconds(60));
        var identity = ProxyIdentity.of(job);
        lenient().when(routingService.resolve(eq(identity))).thenReturn(routing);
        SecurityContextHolder.getContext().setAuthentication(new JobTokenAuthentication(identity, Set.of()));
    }

    private void stubCredential(ProxyRouting routing, LlmModelResolver.ProxyCredential credential) {
        when(resolver.resolveProxyCredential(eq(new LlmModelResolver.ConnectionRef(
                        routing.connectionScope(), routing.connectionId(), routing.modelId(), routing.workspaceId()))))
                .thenReturn(credential);
    }
}
