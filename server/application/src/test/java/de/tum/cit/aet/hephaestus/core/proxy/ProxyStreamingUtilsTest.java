package de.tum.cit.aet.hephaestus.core.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.netty.buffer.PooledByteBufAllocator;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class ProxyStreamingUtilsTest extends BaseUnitTest {

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
    class ConsumeResponse {

        @Test
        void shouldBufferNonSseResponse() {
            byte[] expectedBody = "{\"id\":\"msg_123\"}".getBytes(StandardCharsets.UTF_8);
            HttpHeaders upstreamHeaders = new HttpHeaders();
            upstreamHeaders.setContentType(MediaType.APPLICATION_JSON);

            ClientResponse clientResponse =
                    mockClientResponse(200, upstreamHeaders, MediaType.APPLICATION_JSON, expectedBody);

            StepVerifier.create(
                            ProxyStreamingUtils.consumeResponse(clientResponse, new MockHttpServletResponse(), null))
                    .assertNext(result -> {
                        assertThat(result.status()).isEqualTo(200);
                        assertThat(result.body()).isEqualTo(expectedBody);
                        assertThat(result.streamed()).isFalse();
                    })
                    .verifyComplete();
        }

        @Test
        void shouldStreamSseResponse() {
            HttpHeaders upstreamHeaders = new HttpHeaders();
            upstreamHeaders.setContentType(MediaType.TEXT_EVENT_STREAM);

            var factory = new DefaultDataBufferFactory();
            DataBuffer buf = factory.wrap("data: hello\n\n".getBytes());
            Flux<DataBuffer> sseFlux = Flux.just(buf);

            ClientResponse clientResponse = mockClientResponseWithSseFlux(200, upstreamHeaders, sseFlux);

            StepVerifier.create(
                            ProxyStreamingUtils.consumeResponse(clientResponse, new MockHttpServletResponse(), null))
                    .assertNext(result -> {
                        assertThat(result.status()).isEqualTo(200);
                        assertThat(result.body()).isNull();
                        assertThat(result.streamed()).isTrue();
                        assertThat(result.streamOutcome()).isEqualTo(ProxyStreamingUtils.StreamOutcome.COMPLETED);
                    })
                    .verifyComplete();
        }

        @Test
        void shouldReturnEmptyBodyForEmptyResponse() {
            HttpHeaders upstreamHeaders = new HttpHeaders();
            upstreamHeaders.setContentType(MediaType.APPLICATION_JSON);

            ClientResponse clientResponse = mockClientResponseEmpty(204, upstreamHeaders);

            StepVerifier.create(
                            ProxyStreamingUtils.consumeResponse(clientResponse, new MockHttpServletResponse(), null))
                    .assertNext(result -> {
                        assertThat(result.status()).isEqualTo(204);
                        assertThat(result.body()).isEmpty();
                        assertThat(result.streamed()).isFalse();
                    })
                    .verifyComplete();
        }

        @Test
        void shouldFilterHopByHopFromUpstream() {
            HttpHeaders upstreamHeaders = new HttpHeaders();
            upstreamHeaders.setContentType(MediaType.APPLICATION_JSON);
            upstreamHeaders.set(HttpHeaders.CONNECTION, "keep-alive");
            upstreamHeaders.set(HttpHeaders.TRANSFER_ENCODING, "chunked");
            upstreamHeaders.set("X-Request-Id", "abc123");

            ClientResponse clientResponse =
                    mockClientResponse(200, upstreamHeaders, MediaType.APPLICATION_JSON, "{}".getBytes());

            StepVerifier.create(
                            ProxyStreamingUtils.consumeResponse(clientResponse, new MockHttpServletResponse(), null))
                    .assertNext(result -> {
                        assertThat(result.headers().get(HttpHeaders.CONNECTION)).isNull();
                        assertThat(result.headers().get(HttpHeaders.TRANSFER_ENCODING))
                                .isNull();
                        assertThat(result.headers().getFirst("X-Request-Id")).isEqualTo("abc123");
                    })
                    .verifyComplete();
        }

        @Test
        void shouldForwardErrorStatus() {
            byte[] errorBody = "{\"error\":\"rate_limited\"}".getBytes(StandardCharsets.UTF_8);
            HttpHeaders upstreamHeaders = new HttpHeaders();
            upstreamHeaders.setContentType(MediaType.APPLICATION_JSON);

            ClientResponse clientResponse =
                    mockClientResponse(429, upstreamHeaders, MediaType.APPLICATION_JSON, errorBody);

            StepVerifier.create(
                            ProxyStreamingUtils.consumeResponse(clientResponse, new MockHttpServletResponse(), null))
                    .assertNext(result -> {
                        assertThat(result.status()).isEqualTo(429);
                        assertThat(result.body()).isEqualTo(errorBody);
                    })
                    .verifyComplete();
        }

        @Test
        void shouldDetectSseWithCharset() {
            HttpHeaders upstreamHeaders = new HttpHeaders();
            MediaType sseWithCharset = new MediaType("text", "event-stream", StandardCharsets.UTF_8);
            upstreamHeaders.setContentType(sseWithCharset);

            var factory = new DefaultDataBufferFactory();
            DataBuffer buf = factory.wrap("data: test\n\n".getBytes());
            Flux<DataBuffer> sseFlux = Flux.just(buf);

            ClientResponse clientResponse = mockClientResponseWithSseFlux(200, upstreamHeaders, sseFlux);

            StepVerifier.create(
                            ProxyStreamingUtils.consumeResponse(clientResponse, new MockHttpServletResponse(), null))
                    .assertNext(result -> {
                        assertThat(result.streamed()).isTrue();
                        assertThat(result.body()).isNull();
                    })
                    .verifyComplete();
        }

        @Test
        void shouldTreatNullContentTypeAsNonSse() {
            HttpHeaders upstreamHeaders = new HttpHeaders();

            ClientResponse clientResponse = mockClientResponse(200, upstreamHeaders, null, "plain text".getBytes());

            StepVerifier.create(
                            ProxyStreamingUtils.consumeResponse(clientResponse, new MockHttpServletResponse(), null))
                    .assertNext(result -> {
                        assertThat(result.streamed()).isFalse();
                        assertThat(result.body()).isEqualTo("plain text".getBytes());
                    })
                    .verifyComplete();
        }

        @Test
        void shouldCancelUpstreamAndReleasePooledBuffersWhenClientDisconnects() throws Exception {
            var allocated = new ConcurrentLinkedQueue<NettyDataBuffer>();
            var cancelled = new AtomicBoolean();
            var factory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
            Flux<DataBuffer> body = Flux.range(0, 1024)
                    .<DataBuffer>map(index -> {
                        var buffer = factory.allocateBuffer(32);
                        buffer.write("data: event\n\n".getBytes(StandardCharsets.UTF_8));
                        allocated.add(buffer);
                        return buffer;
                    })
                    .concatWith(Flux.never())
                    .doOnCancel(() -> cancelled.set(true));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_EVENT_STREAM);
            ClientResponse upstream = mockClientResponseWithSseFlux(200, headers, body);
            var response = mock(HttpServletResponse.class);
            var output = mock(ServletOutputStream.class);
            when(response.getOutputStream()).thenReturn(output);
            doThrow(new IOException("Client disconnected")).when(output).write(any(byte[].class));

            StepVerifier.create(ProxyStreamingUtils.consumeResponse(upstream, response, null))
                    .assertNext(result -> {
                        assertThat(result.streamed()).isTrue();
                        assertThat(result.streamOutcome())
                                .isEqualTo(ProxyStreamingUtils.StreamOutcome.CLIENT_DISCONNECTED);
                    })
                    .verifyComplete();

            await().untilAsserted(() -> {
                assertThat(cancelled).isTrue();
                assertThat(allocated).isNotEmpty();
                assertThat(allocated)
                        .allSatisfy(buffer ->
                                assertThat(buffer.getNativeBuffer().refCnt()).isZero());
            });
        }

        @Test
        void shouldReportUpstreamFailureAfterDeliveringAChunkWithoutReplacingCommittedResponse() throws Exception {
            var factory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
            var buffer = factory.allocateBuffer(32);
            buffer.write("data: delivered\n\n".getBytes(StandardCharsets.UTF_8));
            var delivered = Sinks.<Void>empty();
            Flux<DataBuffer> body = Flux.<DataBuffer>just(buffer)
                    .concatWith(delivered.asMono().then(Mono.error(new IOException("private provider failure"))));
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_EVENT_STREAM);
            var response = new MockHttpServletResponse();

            StepVerifier.create(ProxyStreamingUtils.consumeResponse(
                            mockClientResponseWithSseFlux(200, headers, body),
                            response,
                            bytes -> delivered.tryEmitEmpty()))
                    .assertNext(result -> {
                        assertThat(result.status()).isEqualTo(200);
                        assertThat(result.streamOutcome()).isEqualTo(ProxyStreamingUtils.StreamOutcome.STREAM_FAILED);
                        assertThat(result.body()).isNull();
                    })
                    .verifyComplete();

            assertThat(response.isCommitted()).isTrue();
            assertThat(response.getContentAsString()).isEqualTo("data: delivered\n\n");
            assertThat(buffer.getNativeBuffer().refCnt()).isZero();
        }

        private ClientResponse mockClientResponse(
                int status, HttpHeaders headers, @Nullable MediaType contentType, byte[] body) {
            ClientResponse resp = mock(ClientResponse.class);
            ClientResponse.Headers respHeaders = mock(ClientResponse.Headers.class);
            when(resp.headers()).thenReturn(respHeaders);
            when(respHeaders.asHttpHeaders()).thenReturn(headers);
            when(respHeaders.contentType()).thenReturn(Optional.ofNullable(contentType));
            when(resp.statusCode()).thenReturn(HttpStatusCode.valueOf(status));
            when(resp.bodyToMono(byte[].class)).thenReturn(body != null ? Mono.just(body) : Mono.empty());
            return resp;
        }

        private ClientResponse mockClientResponseWithSseFlux(
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

        private ClientResponse mockClientResponseEmpty(int status, HttpHeaders headers) {
            ClientResponse resp = mock(ClientResponse.class);
            ClientResponse.Headers respHeaders = mock(ClientResponse.Headers.class);
            when(resp.headers()).thenReturn(respHeaders);
            when(respHeaders.asHttpHeaders()).thenReturn(headers);
            when(respHeaders.contentType()).thenReturn(Optional.of(MediaType.APPLICATION_JSON));
            when(resp.statusCode()).thenReturn(HttpStatusCode.valueOf(status));
            when(resp.bodyToMono(byte[].class)).thenReturn(Mono.empty());
            return resp;
        }
    }

    @Nested
    class StreamSseToResponse {

        private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

        @Test
        void shouldWriteSseDataToResponse() throws IOException {
            byte[] event1 = "data: hello\n\n".getBytes(StandardCharsets.UTF_8);
            byte[] event2 = "data: world\n\n".getBytes(StandardCharsets.UTF_8);

            Flux<DataBuffer> dataFlux = Flux.just(bufferFactory.wrap(event1), bufferFactory.wrap(event2));

            MockHttpServletResponse response = new MockHttpServletResponse();
            HttpHeaders headers = new HttpHeaders();

            ProxyStreamingUtils.streamSseToResponse(dataFlux, headers, response, 200);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);

            String content = response.getContentAsString();
            assertThat(content).contains("data: hello");
            assertThat(content).contains("data: world");
        }

        @Test
        void shouldSetSseHeaders() {
            Flux<DataBuffer> dataFlux = Flux.empty();
            MockHttpServletResponse response = new MockHttpServletResponse();
            HttpHeaders headers = new HttpHeaders();

            ProxyStreamingUtils.streamSseToResponse(dataFlux, headers, response, 200);

            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, no-store, must-revalidate");
            assertThat(response.getHeader(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
            assertThat(response.getHeader(HttpHeaders.EXPIRES)).isEqualTo("0");
            assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
            assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        }

        @Test
        void shouldForwardUpstreamStatus() {
            Flux<DataBuffer> dataFlux = Flux.empty();
            MockHttpServletResponse response = new MockHttpServletResponse();
            HttpHeaders headers = new HttpHeaders();

            ProxyStreamingUtils.streamSseToResponse(dataFlux, headers, response, 429);

            assertThat(response.getStatus()).isEqualTo(429);
        }

        @Test
        void shouldCopyUpstreamHeadersExcludingManaged() {
            Flux<DataBuffer> dataFlux = Flux.empty();
            MockHttpServletResponse response = new MockHttpServletResponse();

            HttpHeaders upstreamHeaders = new HttpHeaders();
            upstreamHeaders.set("X-Request-Id", "req-123");
            upstreamHeaders.set("X-Anthropic-RateLimit", "10");
            upstreamHeaders.set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            upstreamHeaders.set(HttpHeaders.CONTENT_LENGTH, "999");
            upstreamHeaders.set(HttpHeaders.TRANSFER_ENCODING, "chunked");
            upstreamHeaders.set(HttpHeaders.CACHE_CONTROL, "private");

            ProxyStreamingUtils.streamSseToResponse(dataFlux, upstreamHeaders, response, 200);

            assertThat(response.getHeader("X-Request-Id")).isEqualTo("req-123");
            assertThat(response.getHeader("X-Anthropic-RateLimit")).isEqualTo("10");
            assertThat(response.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, no-store, must-revalidate");
        }

        @Test
        void shouldHandleEmptyFlux() {
            Flux<DataBuffer> dataFlux = Flux.empty();
            MockHttpServletResponse response = new MockHttpServletResponse();
            HttpHeaders headers = new HttpHeaders();

            ProxyStreamingUtils.streamSseToResponse(dataFlux, headers, response, 200);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        }

        @Test
        void shouldHandleErrorFlux() {
            Flux<DataBuffer> dataFlux = Flux.error(new RuntimeException("upstream died"));
            MockHttpServletResponse response = new MockHttpServletResponse();
            HttpHeaders headers = new HttpHeaders();

            assertThat(ProxyStreamingUtils.streamSseToResponse(dataFlux, headers, response, 200))
                    .isEqualTo(ProxyStreamingUtils.StreamOutcome.STREAM_FAILED);

            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        void shouldWriteEventsInOrder() throws IOException {
            Flux<DataBuffer> dataFlux = Flux.range(1, 5)
                    .map(i -> bufferFactory.wrap(("data: event-" + i + "\n\n").getBytes(StandardCharsets.UTF_8)));

            MockHttpServletResponse response = new MockHttpServletResponse();
            HttpHeaders headers = new HttpHeaders();

            ProxyStreamingUtils.streamSseToResponse(dataFlux, headers, response, 200);

            String content = response.getContentAsString();
            int idx1 = content.indexOf("event-1");
            int idx2 = content.indexOf("event-2");
            int idx3 = content.indexOf("event-3");
            int idx4 = content.indexOf("event-4");
            int idx5 = content.indexOf("event-5");

            assertThat(idx1).isLessThan(idx2);
            assertThat(idx2).isLessThan(idx3);
            assertThat(idx3).isLessThan(idx4);
            assertThat(idx4).isLessThan(idx5);
        }
    }

    @Nested
    class StreamSseToResponseWithTap {

        private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

        /**
         * A dropped {@code tap.accept(bytes)} call is invisible everywhere else — the client still gets a
         * perfect stream — while every streamed call silently goes unbilled.
         */
        @Test
        @DisplayName("the tap sees exactly the bytes the client received")
        void tapObservesEveryChunkTheClientGets() throws IOException {
            Flux<DataBuffer> dataFlux = Flux.just(
                    bufferFactory.wrap("data: one\n\n".getBytes(StandardCharsets.UTF_8)),
                    bufferFactory.wrap("data: two\n\n".getBytes(StandardCharsets.UTF_8)));
            MockHttpServletResponse response = new MockHttpServletResponse();
            StringBuilder tapped = new StringBuilder();

            ProxyStreamingUtils.streamSseToResponse(
                    dataFlux,
                    new HttpHeaders(),
                    response,
                    200,
                    bytes -> tapped.append(new String(bytes, StandardCharsets.UTF_8)));

            assertThat(tapped.toString()).isEqualTo(response.getContentAsString());
            assertThat(tapped.toString()).isEqualTo("data: one\n\ndata: two\n\n");
        }

        @Test
        @DisplayName("a tap that throws does not interrupt the stream to the client")
        void aThrowingTapCannotBreakTheStream() throws IOException {
            Flux<DataBuffer> dataFlux = Flux.just(
                    bufferFactory.wrap("data: one\n\n".getBytes(StandardCharsets.UTF_8)),
                    bufferFactory.wrap("data: two\n\n".getBytes(StandardCharsets.UTF_8)));
            MockHttpServletResponse response = new MockHttpServletResponse();

            ProxyStreamingUtils.streamSseToResponse(dataFlux, new HttpHeaders(), response, 200, bytes -> {
                throw new IllegalStateException("bookkeeping blew up");
            });

            assertThat(response.getContentAsString()).isEqualTo("data: one\n\ndata: two\n\n");
        }

        @Test
        @DisplayName("the tap keeps what it saw when the stream errors mid-flight")
        void tapKeepsWhatItSawBeforeAMidStreamError() {
            Flux<DataBuffer> dataFlux = Flux.<DataBuffer>just(
                            bufferFactory.wrap("data: delivered\n\n".getBytes(StandardCharsets.UTF_8)))
                    .concatWith(Flux.error(new RuntimeException("upstream died")));
            MockHttpServletResponse response = new MockHttpServletResponse();
            StringBuilder tapped = new StringBuilder();

            ProxyStreamingUtils.streamSseToResponse(
                    dataFlux,
                    new HttpHeaders(),
                    response,
                    200,
                    bytes -> tapped.append(new String(bytes, StandardCharsets.UTF_8)));

            assertThat(tapped.toString()).isEqualTo("data: delivered\n\n");
        }
    }
}
