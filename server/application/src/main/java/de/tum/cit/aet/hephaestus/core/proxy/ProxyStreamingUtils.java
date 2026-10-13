package de.tum.cit.aet.hephaestus.core.proxy;

import io.netty.handler.timeout.ReadTimeoutException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Shared utilities for HTTP proxy controllers that need SSE streaming support.
 *
 * <p>Used today by the LLM proxy controller; written to be reusable by any future proxy
 * endpoint. All methods are stateless and thread-safe.
 */
public final class ProxyStreamingUtils {

    private static final Logger log = LoggerFactory.getLogger(ProxyStreamingUtils.class);

    /** HTTP hop-by-hop headers that must not be forwarded through a proxy (lowercase for case-insensitive matching). */
    private static final Set<String> HOP_BY_HOP_HEADERS_LOWER = Set.of(
            HttpHeaders.CONNECTION.toLowerCase(Locale.ROOT),
            "keep-alive",
            HttpHeaders.PROXY_AUTHENTICATE.toLowerCase(Locale.ROOT),
            HttpHeaders.PROXY_AUTHORIZATION.toLowerCase(Locale.ROOT),
            HttpHeaders.TE.toLowerCase(Locale.ROOT),
            HttpHeaders.TRAILER.toLowerCase(Locale.ROOT),
            HttpHeaders.TRANSFER_ENCODING.toLowerCase(Locale.ROOT),
            HttpHeaders.UPGRADE.toLowerCase(Locale.ROOT));

    private ProxyStreamingUtils() {}

    /**
     * Filter hop-by-hop headers from a set of HTTP headers.
     * Comparison is case-insensitive per RFC 7230 Section 6.1.
     *
     * @param headers the original headers
     * @return a new {@link HttpHeaders} instance with hop-by-hop headers removed
     */
    public static HttpHeaders filterHopByHopHeaders(HttpHeaders headers) {
        // Build dynamic set: fixed RFC list + any headers named in Connection header value
        Set<String> toStrip = new HashSet<>(HOP_BY_HOP_HEADERS_LOWER);
        List<String> connectionValues = headers.get(HttpHeaders.CONNECTION);
        if (connectionValues != null) {
            for (String val : connectionValues) {
                for (String token : val.split(",", -1)) {
                    String trimmed = token.trim();
                    if (!trimmed.isEmpty()) {
                        toStrip.add(trimmed.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        HttpHeaders filtered = new HttpHeaders();
        headers.forEach((name, values) -> {
            if (!toStrip.contains(name.toLowerCase(Locale.ROOT))) {
                filtered.put(name, values);
            }
        });
        return filtered;
    }

    /**
     * Consume the body within {@code exchangeToMono}'s response lifetime. SSE writes stay off
     * the Netty event loop; cancellation reaches the one upstream subscription, with no replay cache.
     */
    public static Mono<UpstreamResult> consumeResponse(
            ClientResponse clientResp, HttpServletResponse response, @Nullable Consumer<byte[]> tap) {
        return consumeResponse(clientResp, response, tap, UnaryOperator.identity());
    }

    /**
     * As {@link #consumeResponse(ClientResponse, HttpServletResponse, Consumer)}, but reads an SSE body through
     * {@code body}: a caller bounds or cancels the stream there, and the response ends with what it relayed.
     */
    public static Mono<UpstreamResult> consumeResponse(
            ClientResponse clientResp,
            HttpServletResponse response,
            @Nullable Consumer<byte[]> tap,
            UnaryOperator<Flux<DataBuffer>> body) {
        HttpHeaders headers = filterHopByHopHeaders(clientResp.headers().asHttpHeaders());
        int status = clientResp.statusCode().value();
        MediaType contentType = clientResp.headers().contentType().orElse(null);
        if (contentType != null && contentType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)) {
            return Mono.fromCallable(() -> {
                        var outcome = streamSseToResponse(
                                body.apply(clientResp.bodyToFlux(DataBuffer.class)), headers, response, status, tap);
                        return new UpstreamResult(status, headers, null, outcome);
                    })
                    .subscribeOn(Schedulers.boundedElastic());
        }
        return clientResp
                .bodyToMono(byte[].class)
                .defaultIfEmpty(new byte[0])
                .map(bytes -> new UpstreamResult(status, headers, bytes, null));
    }

    /** Total wall-clock time for an SSE stream. Must accommodate LLM thinking + output generation. */
    public static final Duration DEFAULT_SSE_TIMEOUT = Duration.ofMinutes(10);

    /**
     * Stream SSE data directly to the {@link HttpServletResponse} output stream.
     *
     * <p>Bypasses Spring MVC's message converters which do not properly handle SSE
     * streaming with preset Content-Type headers. Uses {@code publishOn(boundedElastic)}
     * to avoid blocking the Netty event loop thread with servlet I/O. Blocks until the
     * stream completes, the client disconnects, or the timeout is reached.
     *
     * @param dataFlux    reactive stream of data buffers from upstream
     * @param respHeaders headers to copy to the response
     * @param response    the servlet response to write to
     * @param statusCode  HTTP status code from upstream
     */
    public static StreamOutcome streamSseToResponse(
            Flux<DataBuffer> dataFlux, HttpHeaders respHeaders, HttpServletResponse response, int statusCode) {
        return streamSseToResponse(dataFlux, respHeaders, response, statusCode, null);
    }

    /**
     * As {@link #streamSseToResponse(Flux, HttpHeaders, HttpServletResponse, int)}, but also hands
     * each chunk's bytes to {@code tap} as they go past. A tee, not a buffer: the tap sees each chunk the
     * proxy received before it is written to the client, so a failed write cannot discard what the
     * upstream already sent. A tap that throws is logged rather than failing the stream.
     *
     * @param tap receives a copy of each chunk's bytes received from upstream; {@code null} to stream
     *     without observing
     */
    public static StreamOutcome streamSseToResponse(
            Flux<DataBuffer> dataFlux,
            HttpHeaders respHeaders,
            HttpServletResponse response,
            int statusCode,
            @Nullable Consumer<byte[]> tap) {
        try {
            response.setStatus(statusCode);
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setCharacterEncoding("UTF-8");

            // Disable caching and reverse-proxy buffering for SSE
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
            response.setHeader(HttpHeaders.PRAGMA, "no-cache");
            response.setHeader(HttpHeaders.EXPIRES, "0");
            response.setHeader("X-Accel-Buffering", "no");

            // Copy upstream headers (excluding ones we set ourselves)
            respHeaders.forEach((name, values) -> {
                if (!HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)
                        && !HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)
                        && !HttpHeaders.TRANSFER_ENCODING.equalsIgnoreCase(name)
                        && !HttpHeaders.CACHE_CONTROL.equalsIgnoreCase(name)) {
                    for (String value : values) {
                        response.addHeader(name, value);
                    }
                }
            });

            response.flushBuffer();
            OutputStream outputStream = response.getOutputStream();
            EventBoundary events = new EventBoundary();

            dataFlux
                    // Move blocking servlet I/O off the Netty event loop thread.
                    .publishOn(Schedulers.boundedElastic())
                    // Discard hooks apply upstream: include publishOn's prefetched queue on cancellation.
                    .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                    .doOnNext(buffer -> {
                        try {
                            byte[] bytes = new byte[buffer.readableByteCount()];
                            buffer.read(bytes);
                            if (tap != null) {
                                try {
                                    tap.accept(bytes);
                                } catch (RuntimeException tapFailure) {
                                    log.warn("SSE tap failed; stream continues: {}", tapFailure.toString());
                                }
                            }
                            outputStream.write(events.complete(bytes));
                            outputStream.flush();
                        } catch (IOException e) {
                            log.debug("Client disconnected during SSE streaming");
                            throw new StreamingException("Client disconnected", e);
                        } finally {
                            DataBufferUtils.release(buffer);
                        }
                    })
                    .blockLast(DEFAULT_SSE_TIMEOUT);
            // A stream that ended cleanly may end without a blank line; what it sent is relayed whole.
            outputStream.write(events.rest());
            outputStream.flush();
            return StreamOutcome.COMPLETED;
        } catch (IOException | StreamingException e) {
            log.debug("SSE client disconnected");
            return StreamOutcome.CLIENT_DISCONNECTED;
        } catch (RuntimeException e) {
            if (readTimedOut(e)) {
                log.debug("SSE upstream sent nothing for its read timeout");
                return StreamOutcome.UPSTREAM_IDLE;
            }
            log.warn("SSE stream failed: type={}", e.getClass().getSimpleName());
            return StreamOutcome.STREAM_FAILED;
        }
    }

    /** The most bytes of one unfinished upstream event the relay holds; a longer event fails the stream. */
    static final int MAX_PENDING_EVENT_BYTES = 1024 * 1024;

    /**
     * Relays whole server-sent events only: the bytes after the last blank line wait for the rest of their event. A
     * provider can stop inside an event; a stream that then ends with an error event of the proxy's own would
     * otherwise glue that event to half an event, and the client could read neither. A stream cut short drops its
     * unfinished event. Lines end with LF, CRLF or CR, as the SSE format allows, also across chunks.
     */
    static final class EventBoundary {

        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        /** No byte of the current line arrived yet: a line ending now ends the event. */
        private boolean lineEmpty = true;
        /** The last byte was a CR, so an LF next completes its CRLF rather than ending another line. */
        private boolean afterCr;

        /**
         * The held bytes and those of {@code chunk} up to and including the last event boundary.
         *
         * @throws IllegalStateException when one unfinished event outgrows {@link #MAX_PENDING_EVENT_BYTES}
         */
        byte[] complete(byte[] chunk) {
            int boundary = -1;
            for (int i = 0; i < chunk.length; i++) {
                byte b = chunk[i];
                if (b == '\n' && afterCr) {
                    afterCr = false;
                    if (boundary == i) {
                        boundary = i + 1;
                    }
                } else if (b == '\n' || b == '\r') {
                    if (lineEmpty) {
                        boundary = i + 1;
                    }
                    lineEmpty = true;
                    afterCr = b == '\r';
                } else {
                    lineEmpty = false;
                    afterCr = false;
                }
            }
            byte[] complete = new byte[0];
            if (boundary < 0) {
                pending.write(chunk, 0, chunk.length);
            } else {
                pending.write(chunk, 0, boundary);
                complete = pending.toByteArray();
                pending.reset();
                pending.write(chunk, boundary, chunk.length - boundary);
            }
            if (pending.size() > MAX_PENDING_EVENT_BYTES) {
                throw new IllegalStateException(
                        "an upstream event exceeded " + MAX_PENDING_EVENT_BYTES + " bytes without ending");
            }
            return complete;
        }

        /** What is held after the last boundary, once the stream ended. */
        byte[] rest() {
            byte[] rest = pending.toByteArray();
            pending.reset();
            return rest;
        }
    }

    /**
     * Whether a read bound ended the exchange: the client's read timeout, or a {@link Flux#timeout} a caller put on
     * the body. Either way the upstream sent no byte for that long.
     */
    public static boolean readTimedOut(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ReadTimeoutException || cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** A fully consumed upstream response: SSE is already written; other bodies remain buffered. */
    @SuppressWarnings("ArrayRecordComponent") // buffered upstream response bytes; never compared, hashed or printed
    public record UpstreamResult(
            int status,
            HttpHeaders headers,
            byte @Nullable [] body,
            @Nullable StreamOutcome streamOutcome) {
        public boolean streamed() {
            return streamOutcome != null;
        }
    }

    /** HTTP headers may already be committed when the stream fails; the terminal outcome is independent. */
    public enum StreamOutcome {
        COMPLETED,
        CLIENT_DISCONNECTED,
        /** The upstream sent no byte for a read bound, so the exchange was cut. */
        UPSTREAM_IDLE,
        STREAM_FAILED
    }
}
