package de.tum.cit.aet.hephaestus.core.proxy;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_DEFAULTS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.netty.buffer.PooledByteBufAllocator;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class ProxyStreamingUtilsTest extends BaseUnitTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);

    private static final NettyDataBufferFactory POOLED = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);

    @Nested
    class FilterHopByHopHeaders {

        @Test
        void shouldRemoveConnection() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONNECTION, "keep-alive");
            headers.set("Content-Type", "application/json");

            HttpHeaders filtered = ProxyStreamingUtils.filterHopByHopHeaders(headers);

            assertThat(filtered.get(HttpHeaders.CONNECTION)).isNull();
            assertThat(filtered.getFirst("Content-Type")).isEqualTo("application/json");
        }

        @Test
        void shouldRemoveTransferEncoding() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.TRANSFER_ENCODING, "chunked");

            HttpHeaders filtered = ProxyStreamingUtils.filterHopByHopHeaders(headers);

            assertThat(filtered.get(HttpHeaders.TRANSFER_ENCODING)).isNull();
        }

        @Test
        void shouldRemoveKeepAlive() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Keep-Alive", "timeout=5");

            HttpHeaders filtered = ProxyStreamingUtils.filterHopByHopHeaders(headers);

            assertThat(filtered.get("Keep-Alive")).isNull();
        }

        @Test
        void shouldBeCaseInsensitive() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("connection", "close");
            headers.set("TRANSFER-ENCODING", "chunked");
            headers.set("keep-alive", "timeout=5");

            HttpHeaders filtered = ProxyStreamingUtils.filterHopByHopHeaders(headers);

            assertThat(filtered.get("connection")).isNull();
            assertThat(filtered.get("TRANSFER-ENCODING")).isNull();
            assertThat(filtered.get("keep-alive")).isNull();
        }

        @Test
        void shouldPreserveNonHopByHopHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            headers.set("Accept", "application/json");
            headers.set("X-Custom-Header", "custom-value");
            headers.set(HttpHeaders.CONNECTION, "keep-alive");

            HttpHeaders filtered = ProxyStreamingUtils.filterHopByHopHeaders(headers);

            assertThat(filtered.getFirst("Content-Type")).isEqualTo("application/json");
            assertThat(filtered.getFirst("Accept")).isEqualTo("application/json");
            assertThat(filtered.getFirst("X-Custom-Header")).isEqualTo("custom-value");
            assertThat(filtered.get(HttpHeaders.CONNECTION)).isNull();
        }

        @Test
        void shouldStripDynamicConnectionHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONNECTION, "keep-alive, X-Custom-Hop");
            headers.set("X-Custom-Hop", "should-be-removed");
            headers.set("X-Safe-Header", "should-remain");

            HttpHeaders filtered = ProxyStreamingUtils.filterHopByHopHeaders(headers);

            assertThat(filtered.get("X-Custom-Hop")).isNull();
            assertThat(filtered.getFirst("X-Safe-Header")).isEqualTo("should-remain");
            assertThat(filtered.get(HttpHeaders.CONNECTION)).isNull();
        }

        @Test
        void shouldHandleEmptyHeaders() {
            HttpHeaders headers = new HttpHeaders();

            HttpHeaders filtered = ProxyStreamingUtils.filterHopByHopHeaders(headers);

            assertThat(filtered.size()).isZero();
        }

        @Test
        void shouldRemoveAllHopByHopHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONNECTION, "keep-alive");
            headers.set("Keep-Alive", "timeout=5");
            headers.set(HttpHeaders.PROXY_AUTHENTICATE, "Basic");
            headers.set(HttpHeaders.PROXY_AUTHORIZATION, "Basic cred");
            headers.set(HttpHeaders.TE, "trailers");
            headers.set(HttpHeaders.TRAILER, "Expires");
            headers.set(HttpHeaders.TRANSFER_ENCODING, "chunked");
            headers.set(HttpHeaders.UPGRADE, "h2c");
            headers.set("X-Survive", "yes");

            HttpHeaders filtered = ProxyStreamingUtils.filterHopByHopHeaders(headers);

            assertThat(filtered.size()).isEqualTo(1);
            assertThat(filtered.getFirst("X-Survive")).isEqualTo("yes");
        }
    }

    @Nested
    class BufferedResponse {

        @Test
        void shouldKeepTheCodecLimitWhenTransformingABufferedBody() {
            var strategies = ExchangeStrategies.builder()
                    .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16))
                    .build();
            var upstream = ClientResponse.create(HttpStatus.OK, strategies)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(new byte[32])))
                    .build();
            assertThatThrownBy(() -> ProxyStreamingUtils.forward(
                            ProxyStreamingUtils.parts(upstream, null, stream -> stream),
                            Duration.ofSeconds(3),
                            new MockHttpServletResponse(),
                            null))
                    .isInstanceOf(DataBufferLimitException.class);
        }

        @Test
        void shouldCancelTheBufferedBodyThroughTheCallersTransform() throws Exception {
            var cancelled = new AtomicBoolean();
            var head = new CountDownLatch(1);
            var bodySubscribed = new CountDownLatch(1);
            var stop = Sinks.<Void>empty();
            var upstream = ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(Flux.<DataBuffer>never()
                            .doOnSubscribe(unused -> bodySubscribed.countDown())
                            .doOnCancel(() -> cancelled.set(true)))
                    .build();
            try (var requests = Executors.newVirtualThreadPerTaskExecutor()) {
                var response = requests.submit(() -> ProxyStreamingUtils.forward(
                        ProxyStreamingUtils.parts(upstream, null, body -> body.takeUntilOther(stop.asMono()))
                                .doOnNext(part -> {
                                    if (part instanceof ProxyStreamingUtils.Head) head.countDown();
                                }),
                        Duration.ofSeconds(10),
                        new MockHttpServletResponse(),
                        null));
                try {
                    assertThat(head.await(3, TimeUnit.SECONDS)).isTrue();
                    assertThat(bodySubscribed.await(3, TimeUnit.SECONDS)).isTrue();
                    stop.tryEmitEmpty();
                    response.get(2, TimeUnit.SECONDS);
                    assertThat(cancelled).isTrue();
                } finally {
                    response.cancel(true);
                }
            }
        }

        @Test
        void shouldBufferNonSseResponse() {
            byte[] expectedBody = "{\"id\":\"msg_123\"}".getBytes(StandardCharsets.UTF_8);
            HttpHeaders upstreamHeaders = new HttpHeaders();
            upstreamHeaders.setContentType(MediaType.APPLICATION_JSON);

            var result = forward(
                    bufferedResponse(200, upstreamHeaders, MediaType.APPLICATION_JSON, Mono.just(expectedBody)),
                    new MockHttpServletResponse(),
                    null);

            assertThat(result.status()).isEqualTo(200);
            assertThat(result.body()).isEqualTo(expectedBody);
            assertThat(result.streamed()).isFalse();
        }

        @Test
        void shouldReturnEmptyBodyForEmptyResponse() {
            HttpHeaders upstreamHeaders = new HttpHeaders();
            upstreamHeaders.setContentType(MediaType.APPLICATION_JSON);

            var result = forward(
                    bufferedResponse(204, upstreamHeaders, MediaType.APPLICATION_JSON, Mono.empty()),
                    new MockHttpServletResponse(),
                    null);

            assertThat(result.status()).isEqualTo(204);
            assertThat(result.body()).isEmpty();
            assertThat(result.streamed()).isFalse();
        }

        @Test
        void shouldFilterHopByHopFromUpstream() {
            HttpHeaders upstreamHeaders = new HttpHeaders();
            upstreamHeaders.setContentType(MediaType.APPLICATION_JSON);
            upstreamHeaders.set(HttpHeaders.CONNECTION, "keep-alive");
            upstreamHeaders.set(HttpHeaders.TRANSFER_ENCODING, "chunked");
            upstreamHeaders.set("X-Request-Id", "abc123");

            var result = forward(
                    bufferedResponse(200, upstreamHeaders, MediaType.APPLICATION_JSON, Mono.just("{}".getBytes(UTF_8))),
                    new MockHttpServletResponse(),
                    null);

            assertThat(result.headers().get(HttpHeaders.CONNECTION)).isNull();
            assertThat(result.headers().get(HttpHeaders.TRANSFER_ENCODING)).isNull();
            assertThat(result.headers().getFirst("X-Request-Id")).isEqualTo("abc123");
        }

        @Test
        void shouldForwardErrorStatus() {
            byte[] errorBody = "{\"error\":\"rate_limited\"}".getBytes(StandardCharsets.UTF_8);
            HttpHeaders upstreamHeaders = new HttpHeaders();
            upstreamHeaders.setContentType(MediaType.APPLICATION_JSON);

            var result = forward(
                    bufferedResponse(429, upstreamHeaders, MediaType.APPLICATION_JSON, Mono.just(errorBody)),
                    new MockHttpServletResponse(),
                    null);

            assertThat(result.status()).isEqualTo(429);
            assertThat(result.body()).isEqualTo(errorBody);
        }

        @Test
        void shouldTreatNullContentTypeAsNonSse() {
            var result = forward(
                    bufferedResponse(200, new HttpHeaders(), null, Mono.just("plain text".getBytes(UTF_8))),
                    new MockHttpServletResponse(),
                    null);

            assertThat(result.streamed()).isFalse();
            assertThat(result.body()).isEqualTo("plain text".getBytes(UTF_8));
        }

        @Test
        @DisplayName("a buffered body still unread at the limit is thrown, and the response is never touched")
        void shouldThrowABufferedBodyStillUnreadAtTheLimit() {
            var cancelled = new AtomicBoolean();
            HttpHeaders upstreamHeaders = new HttpHeaders();
            upstreamHeaders.setContentType(MediaType.APPLICATION_JSON);
            var upstream = bufferedResponse(
                    200,
                    upstreamHeaders,
                    MediaType.APPLICATION_JSON,
                    Mono.<byte[]>never().doOnCancel(() -> cancelled.set(true)));
            var recorded = recording(new CountDownLatch(0));

            assertThatThrownBy(() -> ProxyStreamingUtils.forward(
                            ProxyStreamingUtils.parts(upstream, null),
                            Duration.ofSeconds(1),
                            recorded.response(),
                            null))
                    .hasCauseInstanceOf(TimeoutException.class);

            assertThat(cancelled).isTrue();
            assertThat(recorded.callers()).isEmpty();
        }
    }

    @Nested
    class StreamedResponse {

        private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

        @Test
        void shouldStreamSseResponse() throws IOException {
            var response = new MockHttpServletResponse();

            var result = forward(sse(Flux.just(bufferFactory.wrap("data: hello\n\n".getBytes(UTF_8)))), response, null);

            assertThat(result.status()).isEqualTo(200);
            assertThat(result.body()).isNull();
            assertThat(result.streamed()).isTrue();
            assertThat(result.streamOutcome()).isEqualTo(ProxyStreamingUtils.StreamOutcome.COMPLETED);
            assertThat(response.getContentAsString()).isEqualTo("data: hello\n\n");
            assertThat(response.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        }

        @Test
        void shouldDetectSseWithCharset() {
            HttpHeaders upstreamHeaders = new HttpHeaders();
            upstreamHeaders.setContentType(new MediaType("text", "event-stream", StandardCharsets.UTF_8));

            var result = forward(
                    mockClientResponseWithSseFlux(
                            200, upstreamHeaders, Flux.just(bufferFactory.wrap("data: test\n\n".getBytes(UTF_8)))),
                    new MockHttpServletResponse(),
                    null);

            assertThat(result.streamed()).isTrue();
            assertThat(result.body()).isNull();
        }

        @Test
        void shouldSetSseHeaders() {
            var response = new MockHttpServletResponse();

            forward(sse(Flux.empty()), response, null);

            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, no-store, must-revalidate");
            assertThat(response.getHeader(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
            assertThat(response.getHeader(HttpHeaders.EXPIRES)).isEqualTo("0");
            assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
            assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        }

        @Test
        void shouldForwardUpstreamStatus() {
            HttpHeaders upstreamHeaders = new HttpHeaders();
            upstreamHeaders.setContentType(MediaType.TEXT_EVENT_STREAM);
            var response = new MockHttpServletResponse();

            forward(mockClientResponseWithSseFlux(429, upstreamHeaders, Flux.empty()), response, null);

            assertThat(response.getStatus()).isEqualTo(429);
        }

        @Test
        void shouldCopyUpstreamHeadersExcludingManaged() {
            HttpHeaders upstreamHeaders = new HttpHeaders();
            upstreamHeaders.set("X-Request-Id", "req-123");
            upstreamHeaders.set("X-Anthropic-RateLimit", "10");
            upstreamHeaders.set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            upstreamHeaders.set(HttpHeaders.CONTENT_LENGTH, "999");
            upstreamHeaders.set(HttpHeaders.CACHE_CONTROL, "private");
            var response = new MockHttpServletResponse();

            forward(mockClientResponseWithSseFlux(200, upstreamHeaders, Flux.empty()), response, null);

            assertThat(response.getHeader("X-Request-Id")).isEqualTo("req-123");
            assertThat(response.getHeader("X-Anthropic-RateLimit")).isEqualTo("10");
            assertThat(response.getHeader(HttpHeaders.CONTENT_LENGTH)).isNull();
            assertThat(response.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, no-store, must-revalidate");
        }

        @Test
        void shouldHandleEmptyStream() {
            var response = new MockHttpServletResponse();

            var result = forward(sse(Flux.empty()), response, null);

            assertThat(result.streamOutcome()).isEqualTo(ProxyStreamingUtils.StreamOutcome.COMPLETED);
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        }

        @Test
        @DisplayName(
                "a body that fails at once still keeps its head: the stream has started and its outcome says it failed")
        void shouldHandleAFailedStream() {
            var response = new MockHttpServletResponse();

            var result = forward(sse(Flux.error(new RuntimeException("upstream died"))), response, null);

            assertThat(result.streamOutcome()).isEqualTo(ProxyStreamingUtils.StreamOutcome.STREAM_FAILED);
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.isCommitted()).isTrue();
        }

        @Test
        void shouldWriteEventsInOrder() throws IOException {
            var response = new MockHttpServletResponse();

            forward(
                    sse(Flux.range(1, 5).map(i -> bufferFactory.wrap(("data: event-" + i + "\n\n").getBytes(UTF_8)))),
                    response,
                    null);

            assertThat(response.getContentAsString())
                    .isEqualTo("data: event-1\n\ndata: event-2\n\ndata: event-3\n\ndata: event-4\n\ndata: event-5\n\n");
        }

        @Test
        void shouldReportUpstreamFailureAfterDeliveringAChunkWithoutReplacingCommittedResponse() throws Exception {
            var buffer = pooled("data: delivered\n\n");
            var delivered = Sinks.<Void>empty();
            Flux<DataBuffer> body = Flux.<DataBuffer>just(buffer)
                    .concatWith(delivered.asMono().then(Mono.error(new IOException("private provider failure"))));
            var response = new MockHttpServletResponse();

            var result = forward(sse(body), response, bytes -> delivered.tryEmitEmpty());

            assertThat(result.status()).isEqualTo(200);
            assertThat(result.streamOutcome()).isEqualTo(ProxyStreamingUtils.StreamOutcome.STREAM_FAILED);
            assertThat(result.body()).isNull();
            assertThat(response.isCommitted()).isTrue();
            assertThat(response.getContentAsString()).isEqualTo("data: delivered\n\n");
            assertThat(buffer.getNativeBuffer().refCnt()).isZero();
        }

        @Test
        @DisplayName(
                "a client that disconnects cancels the upstream before the call returns, and every buffer is released")
        void shouldCancelUpstreamAndReleasePooledBuffersWhenClientDisconnects() throws Exception {
            var allocated = new ConcurrentLinkedQueue<NettyDataBuffer>();
            var cancelled = new AtomicBoolean();
            Flux<DataBuffer> body = Flux.range(0, 1024)
                    .<DataBuffer>map(index -> {
                        var buffer = pooled("data: event\n\n");
                        allocated.add(buffer);
                        return buffer;
                    })
                    .concatWith(Flux.never())
                    .doOnCancel(() -> cancelled.set(true));
            var response = mock(HttpServletResponse.class);
            var output = mock(ServletOutputStream.class);
            when(response.getOutputStream()).thenReturn(output);
            doThrow(new IOException("Client disconnected")).when(output).write(any(byte[].class));

            var result = forward(sse(body), response, null);

            assertThat(result.streamOutcome()).isEqualTo(ProxyStreamingUtils.StreamOutcome.CLIENT_DISCONNECTED);
            assertThat(cancelled).isTrue();
            // Read one part at a time: the body is never read far ahead of the client.
            assertThat(allocated).hasSizeBetween(1, 2);
            assertThat(allocated)
                    .allSatisfy(buffer ->
                            assertThat(buffer.getNativeBuffer().refCnt()).isZero());
        }
    }

    @Nested
    class Tap {

        private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

        /**
         * A dropped {@code tap.accept(bytes)} call is invisible everywhere else — the client still gets a
         * perfect stream — while every streamed call silently goes unbilled.
         */
        @Test
        @DisplayName("the tap sees exactly the bytes the client received")
        void tapObservesEveryChunkTheClientGets() throws IOException {
            var response = new MockHttpServletResponse();
            StringBuilder tapped = new StringBuilder();

            forward(
                    sse(Flux.just(
                            bufferFactory.wrap("data: one\n\n".getBytes(UTF_8)),
                            bufferFactory.wrap("data: two\n\n".getBytes(UTF_8)))),
                    response,
                    bytes -> tapped.append(new String(bytes, UTF_8)));

            assertThat(tapped.toString()).isEqualTo(response.getContentAsString());
            assertThat(tapped.toString()).isEqualTo("data: one\n\ndata: two\n\n");
        }

        @Test
        @DisplayName("a tap that throws does not interrupt the stream to the client")
        void aThrowingTapCannotBreakTheStream() throws IOException {
            var response = new MockHttpServletResponse();

            var result = forward(
                    sse(Flux.just(
                            bufferFactory.wrap("data: one\n\n".getBytes(UTF_8)),
                            bufferFactory.wrap("data: two\n\n".getBytes(UTF_8)))),
                    response,
                    bytes -> {
                        throw new IllegalStateException("bookkeeping blew up");
                    });

            assertThat(result.streamOutcome()).isEqualTo(ProxyStreamingUtils.StreamOutcome.COMPLETED);
            assertThat(response.getContentAsString()).isEqualTo("data: one\n\ndata: two\n\n");
        }

        /**
         * The failure arrives while the usage frame still waits to be read; it must not overtake the frame, or the
         * usage the upstream did send would go unbilled.
         */
        @Test
        @DisplayName("a usage frame received just before the upstream fails is still tapped and written")
        void tapKeepsWhatItSawBeforeAMidStreamError() throws IOException {
            var content = pooled("data: {\"delta\":\"hi\"}\n\n");
            var usage = pooled("data: {\"usage\":{\"prompt_tokens\":3}}\n\n");
            Flux<DataBuffer> body =
                    Flux.<DataBuffer>just(content, usage).concatWith(Flux.error(new IOException("connection reset")));
            var response = new MockHttpServletResponse();
            StringBuilder tapped = new StringBuilder();

            var result = forward(sse(body), response, bytes -> tapped.append(new String(bytes, UTF_8)));

            assertThat(result.streamOutcome()).isEqualTo(ProxyStreamingUtils.StreamOutcome.STREAM_FAILED);
            assertThat(tapped.toString())
                    .isEqualTo("data: {\"delta\":\"hi\"}\n\ndata: {\"usage\":{\"prompt_tokens\":3}}\n\n");
            assertThat(response.getContentAsString()).isEqualTo(tapped.toString());
            assertThat(content.getNativeBuffer().refCnt()).isZero();
            assertThat(usage.getNativeBuffer().refCnt()).isZero();
        }

        @Test
        @DisplayName("a received usage frame is tapped even when the write to the client then fails")
        void tapKeepsAReceivedUsageFrameWhoseFlushFails() throws IOException {
            var content = pooled("data: {\"delta\":\"hi\"}\n\n");
            var usage = pooled("data: {\"usage\":{\"prompt_tokens\":3}}\n\n");
            var subscriptions = new AtomicInteger();
            Flux<DataBuffer> body =
                    Flux.<DataBuffer>just(content, usage).doOnSubscribe(s -> subscriptions.incrementAndGet());
            var response = mock(HttpServletResponse.class);
            var output = mock(ServletOutputStream.class);
            when(response.getOutputStream()).thenReturn(output);
            doNothing()
                    .doThrow(new IOException("Client disconnected"))
                    .when(output)
                    .flush();
            StringBuilder tapped = new StringBuilder();

            var result = forward(sse(body), response, bytes -> tapped.append(new String(bytes, UTF_8)));

            assertThat(result.streamOutcome()).isEqualTo(ProxyStreamingUtils.StreamOutcome.CLIENT_DISCONNECTED);
            assertThat(tapped.toString())
                    .isEqualTo("data: {\"delta\":\"hi\"}\n\ndata: {\"usage\":{\"prompt_tokens\":3}}\n\n");
            assertThat(subscriptions).hasValue(1);
            assertThat(content.getNativeBuffer().refCnt()).isZero();
            assertThat(usage.getNativeBuffer().refCnt()).isZero();
        }

        @Test
        @DisplayName("a client that disconnects before the usage frame leaves the tap without it")
        void tapHasNoUsageWhenTheClientDisconnectsFirst() throws Exception {
            var content = pooled("data: {\"delta\":\"hi\"}\n\n");
            var cancelled = new AtomicBoolean();
            var subscriptions = new AtomicInteger();
            Flux<DataBuffer> body = Flux.<DataBuffer>just(content)
                    .concatWith(Flux.never())
                    .doOnSubscribe(s -> subscriptions.incrementAndGet())
                    .doOnCancel(() -> cancelled.set(true));
            var response = mock(HttpServletResponse.class);
            var output = mock(ServletOutputStream.class);
            when(response.getOutputStream()).thenReturn(output);
            doThrow(new IOException("Client disconnected")).when(output).write(any(byte[].class));
            StringBuilder tapped = new StringBuilder();

            var result = forward(sse(body), response, bytes -> tapped.append(new String(bytes, UTF_8)));

            assertThat(result.streamOutcome()).isEqualTo(ProxyStreamingUtils.StreamOutcome.CLIENT_DISCONNECTED);
            assertThat(tapped.toString()).isEqualTo("data: {\"delta\":\"hi\"}\n\n");
            assertThat(subscriptions).hasValue(1);
            assertThat(cancelled).isTrue();
            assertThat(content.getNativeBuffer().refCnt()).isZero();
        }
    }

    @Nested
    class Limits {

        @Test
        @DisplayName("a limit that passes before the head is thrown, cancels the upstream, and touches no response")
        void shouldThrowALimitThatPassesBeforeTheHead() {
            var cancelled = new AtomicBoolean();
            var recorded = recording(new CountDownLatch(0));

            assertThatThrownBy(() -> ProxyStreamingUtils.forward(
                            Flux.<ProxyStreamingUtils.Part>never().doOnCancel(() -> cancelled.set(true)),
                            Duration.ofSeconds(1),
                            recorded.response(),
                            null))
                    .hasCauseInstanceOf(TimeoutException.class);

            assertThat(cancelled).isTrue();
            assertThat(recorded.callers()).isEmpty();
        }

        @Test
        @DisplayName("an upstream failure before the head is thrown, leaving the response to the caller")
        void shouldThrowAFailureBeforeTheHead() {
            var recorded = recording(new CountDownLatch(0));

            assertThatThrownBy(() -> ProxyStreamingUtils.forward(
                            Flux.error(new IOException("connection refused")), LIMIT, recorded.response(), null))
                    .hasCauseInstanceOf(IOException.class);

            assertThat(recorded.callers()).isEmpty();
        }

        @Test
        @DisplayName("an exchange that ends without a head is thrown, leaving the response to the caller")
        void shouldThrowAnExchangeWithoutAHead() {
            var recorded = recording(new CountDownLatch(0));

            assertThatThrownBy(() -> ProxyStreamingUtils.forward(Flux.empty(), LIMIT, recorded.response(), null))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(recorded.callers()).isEmpty();
        }

        @Test
        @DisplayName("the limit ends a stalled stream as timed out, cancelling the upstream before the call returns")
        void shouldTimeOutAStalledUpstreamAfterItsCommittedChunk() throws IOException {
            var delivered = pooled("data: delivered\n\n");
            var cancelled = new AtomicBoolean();
            Flux<DataBuffer> body =
                    Flux.<DataBuffer>just(delivered).concatWith(Flux.never()).doOnCancel(() -> cancelled.set(true));
            var response = new MockHttpServletResponse();

            var result = ProxyStreamingUtils.forward(
                    ProxyStreamingUtils.parts(sse(body), null), Duration.ofSeconds(1), response, null);

            assertThat(result.streamOutcome()).isEqualTo(ProxyStreamingUtils.StreamOutcome.TIMED_OUT);
            // The committed 200 and its bytes stay as they are; the outcome, not the status, says the stream failed.
            assertThat(response.isCommitted()).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEqualTo("data: delivered\n\n");
            assertThat(cancelled).isTrue();
            assertThat(delivered.getNativeBuffer().refCnt()).isZero();
        }

        /** The mentor's stream limit: counted from the head, and shorter than its whole-exchange limit. */
        @Test
        @DisplayName("a body limit ends a stalled stream as timed out within a longer exchange limit")
        void shouldEndAStalledBodyAtItsOwnLimit() {
            var cancelled = new AtomicBoolean();
            Flux<DataBuffer> body = Flux.<DataBuffer>never().doOnCancel(() -> cancelled.set(true));

            var result = ProxyStreamingUtils.forward(
                    ProxyStreamingUtils.parts(sse(body), Duration.ofSeconds(1)),
                    Duration.ofSeconds(30),
                    new MockHttpServletResponse(),
                    null);

            assertThat(result.streamOutcome()).isEqualTo(ProxyStreamingUtils.StreamOutcome.TIMED_OUT);
            assertThat(cancelled).isTrue();
        }

        /**
         * The write is held past the limit and opened only by another thread once the upstream is cancelled, so
         * the cancel cannot have waited for the write. The calling thread makes every response call, all of them
         * before it returns.
         */
        @Test
        @DisplayName(
                "the limit cancels the upstream while a write is held, and only the calling thread touches the response")
        void shouldCancelTheUpstreamWhileAWriteIsHeld() throws Exception {
            var allocated = new ConcurrentLinkedQueue<NettyDataBuffer>();
            var cancelled = new CountDownLatch(1);
            Flux<DataBuffer> body = Flux.range(0, 64)
                    .<DataBuffer>map(index -> {
                        var buffer = pooled("data: event\n\n");
                        allocated.add(buffer);
                        return buffer;
                    })
                    .concatWith(Flux.never())
                    .doOnCancel(cancelled::countDown);
            var release = new CountDownLatch(1);
            var recorded = recording(release);
            var cancelledWhileHeld = new AtomicBoolean();
            var releaser = new Thread(() -> {
                try {
                    cancelledWhileHeld.set(recorded.writing().await(10, TimeUnit.SECONDS)
                            && cancelled.await(10, TimeUnit.SECONDS)
                            && recorded.writes().get() == 1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    release.countDown();
                }
            });
            releaser.start();
            var tapped = new AtomicInteger();

            var result = ProxyStreamingUtils.forward(
                    ProxyStreamingUtils.parts(sse(body), null),
                    Duration.ofSeconds(1),
                    recorded.response(),
                    bytes -> tapped.incrementAndGet());
            int callsAtReturn = recorded.callers().size();
            releaser.join(10_000);

            assertThat(cancelledWhileHeld).isTrue();
            assertThat(result.streamOutcome()).isEqualTo(ProxyStreamingUtils.StreamOutcome.TIMED_OUT);
            assertThat(recorded.callers()).isNotEmpty().containsOnly(Thread.currentThread());
            assertThat(recorded.callers()).hasSize(callsAtReturn);
            // Past the limit only the one part already received is written: it is tapped like any other.
            assertThat(recorded.writes()).hasValue(2);
            assertThat(tapped).hasValue(2);
            assertThat(allocated).hasSize(2);
            assertThat(allocated)
                    .allSatisfy(buffer ->
                            assertThat(buffer.getNativeBuffer().refCnt()).isZero());
        }

        @Test
        @DisplayName("a stream whose upstream emits on another thread is still written only by the calling thread")
        void shouldWriteOnlyOnTheCallingThread() {
            var first = pooled("data: one\n\n");
            var second = pooled("data: two\n\n");
            var emitters = new ConcurrentLinkedQueue<Thread>();
            Flux<DataBuffer> body = Flux.<DataBuffer>just(first, second)
                    .delayElements(Duration.ofMillis(10))
                    .doOnNext(buffer -> emitters.add(Thread.currentThread()));
            var recorded = recording(new CountDownLatch(0));

            var result = forward(sse(body), recorded.response(), null);

            assertThat(result.streamOutcome()).isEqualTo(ProxyStreamingUtils.StreamOutcome.COMPLETED);
            assertThat(emitters).hasSize(2).doesNotContain(Thread.currentThread());
            assertThat(recorded.writes()).hasValue(2);
            assertThat(recorded.callers()).containsOnly(Thread.currentThread());
            assertThat(first.getNativeBuffer().refCnt()).isZero();
            assertThat(second.getNativeBuffer().refCnt()).isZero();
        }

        @Test
        @DisplayName("a stream that ends before its limit is completed")
        void shouldCompleteAStreamThatEndsBeforeItsDeadline() throws IOException {
            var content = pooled("data: done\n\n");
            var response = new MockHttpServletResponse();

            var result = forward(sse(Flux.just(content)), response, null);

            assertThat(result.streamOutcome()).isEqualTo(ProxyStreamingUtils.StreamOutcome.COMPLETED);
            assertThat(response.getContentAsString()).isEqualTo("data: done\n\n");
            assertThat(content.getNativeBuffer().refCnt()).isZero();
        }

        @Test
        @DisplayName("only a typed timeout cause is a timeout: a failure whose words name one is not")
        void shouldReportAFailureWithoutATypedTimeoutCauseAsFailed() {
            var worded = Flux.<DataBuffer>error(
                    new IllegalStateException("Timeout on blocking read for 600000000000 NANOSECONDS"));

            assertThat(forward(sse(worded), new MockHttpServletResponse(), null).streamOutcome())
                    .isEqualTo(ProxyStreamingUtils.StreamOutcome.STREAM_FAILED);
        }

        @Test
        @DisplayName("an upstream typed timeout is also a typed timeout: the outcome does not name the hop")
        void shouldReportATypedUpstreamTimeoutAsTimedOut() {
            var upstream = Flux.<DataBuffer>error(new TimeoutException("upstream"));

            assertThat(forward(sse(upstream), new MockHttpServletResponse(), null)
                            .streamOutcome())
                    .isEqualTo(ProxyStreamingUtils.StreamOutcome.TIMED_OUT);
        }

        @Test
        @DisplayName("a nested timeout without a direct typed cause remains a stream failure")
        void shouldNotClassifyANestedTimeoutAsADirectTimeout() {
            var nested = Flux.<DataBuffer>error(
                    new RuntimeException(new IllegalStateException(new TimeoutException("nested"))));

            assertThat(forward(sse(nested), new MockHttpServletResponse(), null).streamOutcome())
                    .isEqualTo(ProxyStreamingUtils.StreamOutcome.STREAM_FAILED);
        }

        @Test
        @DisplayName("a far deadline keeps all of its time; a passed one leaves none")
        void shouldGiveAFarDeadlineAllOfItsTimeAndAPassedOneNone() {
            assertThat(ProxyStreamingUtils.remainingUntil(Instant.now().plus(Duration.ofHours(3))))
                    .isGreaterThan(ProxyStreamingUtils.DEFAULT_SSE_TIMEOUT);
            assertThat(ProxyStreamingUtils.remainingUntil(Instant.now().minusSeconds(5)))
                    .isZero();
        }
    }

    private static ProxyStreamingUtils.UpstreamResult forward(
            ClientResponse upstream, HttpServletResponse response, @Nullable Consumer<byte[]> tap) {
        return ProxyStreamingUtils.forward(ProxyStreamingUtils.parts(upstream, null), LIMIT, response, tap);
    }

    /** Every call on a response or its output stream, by thread; each write waits until {@code release} opens. */
    private record Recording(
            HttpServletResponse response, Queue<Thread> callers, CountDownLatch writing, AtomicInteger writes) {}

    private static Recording recording(CountDownLatch release) {
        var callers = new ConcurrentLinkedQueue<Thread>();
        var writing = new CountDownLatch(1);
        var writes = new AtomicInteger();
        ServletOutputStream output = mock(ServletOutputStream.class, call -> {
            callers.add(Thread.currentThread());
            if (call.getMethod().getName().equals("write")) {
                writes.incrementAndGet();
                writing.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return RETURNS_DEFAULTS.answer(call);
        });
        HttpServletResponse response = mock(HttpServletResponse.class, call -> {
            callers.add(Thread.currentThread());
            return call.getMethod().getName().equals("getOutputStream") ? output : RETURNS_DEFAULTS.answer(call);
        });
        return new Recording(response, callers, writing, writes);
    }

    private static NettyDataBuffer pooled(String frame) {
        var buffer = POOLED.allocateBuffer(64);
        buffer.write(frame.getBytes(UTF_8));
        return buffer;
    }

    private static ClientResponse sse(Flux<DataBuffer> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_EVENT_STREAM);
        return mockClientResponseWithSseFlux(200, headers, body);
    }

    private static ClientResponse bufferedResponse(
            int status, HttpHeaders headers, @Nullable MediaType contentType, Mono<byte[]> body) {
        return ClientResponse.create(HttpStatusCode.valueOf(status))
                .headers(copy -> {
                    copy.addAll(headers);
                    if (contentType != null) copy.setContentType(contentType);
                })
                .body(body.<DataBuffer>map(DefaultDataBufferFactory.sharedInstance::wrap)
                        .flux())
                .build();
    }

    private static ClientResponse mockClientResponseWithSseFlux(
            int status, HttpHeaders headers, Flux<DataBuffer> sseFlux) {
        MediaType ct = headers.getContentType() != null ? headers.getContentType() : MediaType.TEXT_EVENT_STREAM;
        ClientResponse resp = mock(ClientResponse.class);
        ClientResponse.Headers respHeaders = mock(ClientResponse.Headers.class);
        when(resp.headers()).thenReturn(respHeaders);
        when(respHeaders.asHttpHeaders()).thenReturn(headers);
        when(respHeaders.contentType()).thenReturn(Optional.of(ct));
        when(resp.statusCode()).thenReturn(HttpStatusCode.valueOf(status));
        when(resp.bodyToFlux(DataBuffer.class)).thenReturn(sseFlux);
        return resp;
    }
}
