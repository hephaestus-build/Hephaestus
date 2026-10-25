package de.tum.cit.aet.hephaestus.core.proxy;

import io.netty.handler.timeout.ReadTimeoutException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.scheduler.Schedulers;

/**
 * Shared utilities for HTTP proxy controllers that need SSE streaming support.
 *
 * <p>Used today by the LLM proxy; written to be reusable by any future proxy endpoint. All methods are stateless
 * and thread-safe.
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
     * Total wall-clock time for an SSE body that has no deadline of its own, counted from when the body starts.
     * Must accommodate LLM thinking + output generation.
     */
    public static final Duration DEFAULT_SSE_TIMEOUT = Duration.ofMinutes(10);

    /** One part of an upstream response, in order: its head, then its body. */
    public sealed interface Part permits Head, Body {}

    /**
     * The upstream status and headers, hop-by-hop headers removed.
     *
     * @param stream whether the body is an SSE stream, which is forwarded as it arrives; any other body is buffered
     */
    public record Head(int status, HttpHeaders headers, boolean stream) implements Part {}

    /** One SSE chunk as it arrived, or the whole body of any other response. */
    @SuppressWarnings("ArrayRecordComponent") // upstream bytes; never compared, hashed or printed
    public record Body(byte[] bytes) implements Part {}

    /**
     * The parts of one upstream response, for {@code exchangeToFlux}: the response lives exactly as long as they are
     * read. Each SSE chunk is copied out and its buffer released at once, so a part that waits in a queue, or is
     * dropped when the exchange is cancelled, holds no pooled memory.
     *
     * @param bodyLimit how long an SSE body may run once it starts; {@code null} when the caller's own limit on the
     *     whole exchange is the only one
     */
    public static Flux<Part> parts(ClientResponse clientResp, @Nullable Duration bodyLimit) {
        return parts(clientResp, bodyLimit, UnaryOperator.identity());
    }

    /**
     * As {@link #parts(ClientResponse, Duration)}, but reads an SSE body through {@code body}: a caller bounds or
     * cancels the stream there, and the response ends with what it relayed.
     */
    public static Flux<Part> parts(
            ClientResponse clientResp, @Nullable Duration bodyLimit, UnaryOperator<Flux<DataBuffer>> body) {
        MediaType contentType = clientResp.headers().contentType().orElse(null);
        var head = new Head(
                clientResp.statusCode().value(),
                filterHopByHopHeaders(clientResp.headers().asHttpHeaders()),
                contentType != null && contentType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
        if (!head.stream()) {
            return Flux.<Part>concat(
                    Mono.just(head),
                    clientResp
                            .bodyToMono(byte[].class)
                            .defaultIfEmpty(new byte[0])
                            .map(Body::new));
        }
        Flux<Part> chunks = body.apply(clientResp.bodyToFlux(DataBuffer.class))
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                .map(ProxyStreamingUtils::copyAndRelease);
        return Flux.<Part>concat(Mono.just(head), bodyLimit == null ? chunks : endingAfter(chunks, bodyLimit));
    }

    /**
     * Runs one exchange on the calling thread, which alone touches the servlet response: its status, headers, flushes
     * and writes. The exchange is read one part at a time, so at most one part waits while a write is slow.
     *
     * <p>{@code limit} bounds the whole exchange, waiting for the head and then the body, and is counted from now. It
     * cancels the upstream when it passes, even while a write to the client is still under way; that write itself is
     * bounded only by the servlet container. Closing the exchange on return cancels whatever is still upstream.
     *
     * <p>A failure, the limit included, is read only after the part received before it, so a chunk that arrived just
     * before the upstream failed is still tapped and written; past the limit that is at most the one waiting part.
     *
     * <p>Until an SSE head is written, a failure or the limit is thrown, so the caller still owns the response. After
     * it, the outcome is returned instead: the status and the bytes already sent stand. The tap sees each chunk
     * before it is written to the client, so a failed write cannot discard what the upstream already sent; a tap that
     * throws is logged rather than failing the stream.
     *
     * @param tap receives a copy of each SSE chunk; {@code null} to stream without observing
     */
    public static UpstreamResult forward(
            Flux<Part> exchange,
            Duration limit,
            @Nullable HttpServletResponse response,
            @Nullable Consumer<byte[]> tap) {
        try (Stream<Signal<Part>> signals =
                endingAfter(exchange, limit).materialize().toStream(1)) {
            Iterator<Signal<Part>> parts = signals.iterator();
            if (!(nextPart(parts) instanceof Head head)) {
                throw new IllegalStateException("The upstream response has no head");
            }
            if (head.stream()) {
                // Only a servlet caller can relay a stream; an in-process caller asked for a body.
                if (response == null)
                    throw new IllegalStateException("The upstream answered a body request with a stream");
                return new UpstreamResult(head.status(), head.headers(), null, writeStream(head, parts, response, tap));
            }
            var body = new ByteArrayOutputStream();
            for (Part part = nextPart(parts); part != null; part = nextPart(parts)) {
                if (part instanceof Body chunk) body.writeBytes(chunk.bytes());
            }
            return new UpstreamResult(head.status(), head.headers(), body.toByteArray(), null);
        }
    }

    /** The next part, or {@code null} once the exchange has completed; a failure is thrown. */
    private static @Nullable Part nextPart(Iterator<Signal<Part>> parts) {
        Signal<Part> signal = parts.next();
        Throwable failure = signal.getThrowable();
        if (failure != null) throw Exceptions.propagate(failure);
        return signal.get();
    }

    private static StreamOutcome writeStream(
            Head head, Iterator<Signal<Part>> parts, HttpServletResponse response, @Nullable Consumer<byte[]> tap) {
        try {
            response.setStatus(head.status());
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setCharacterEncoding("UTF-8");

            // Disable caching and reverse-proxy buffering for SSE
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
            response.setHeader(HttpHeaders.PRAGMA, "no-cache");
            response.setHeader(HttpHeaders.EXPIRES, "0");
            response.setHeader("X-Accel-Buffering", "no");

            // Copy upstream headers (excluding ones we set ourselves)
            head.headers().forEach((name, values) -> {
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
            for (Part part = nextPart(parts); part != null; part = nextPart(parts)) {
                if (!(part instanceof Body chunk)) continue;
                byte[] bytes = chunk.bytes();
                if (tap != null) {
                    try {
                        tap.accept(bytes);
                    } catch (RuntimeException tapFailure) {
                        log.warn("SSE tap failed; stream continues: {}", tapFailure.toString());
                    }
                }
                outputStream.write(events.complete(bytes));
                outputStream.flush();
            }
            // A stream that ended cleanly may end without a blank line; what it sent is relayed whole.
            byte[] rest = events.rest();
            if (rest.length > 0) outputStream.write(rest);
            outputStream.flush();
            return StreamOutcome.COMPLETED;
        } catch (IOException e) {
            log.debug("SSE client disconnected");
            return StreamOutcome.CLIENT_DISCONNECTED;
        } catch (RuntimeException e) {
            if (readTimedOut(e)) {
                log.debug("SSE upstream sent nothing for its read timeout");
                return StreamOutcome.UPSTREAM_IDLE;
            }
            // A passed limit is thrown as a RuntimeException whose direct cause is the TimeoutException. The
            // direct typed cause is the only signal used; it does not say which hop timed out.
            if (e.getCause() instanceof TimeoutException typed) {
                log.warn(
                        "SSE stream timed out: type={}, cause={}",
                        e.getClass().getSimpleName(),
                        typed.getClass().getSimpleName());
                return StreamOutcome.TIMED_OUT;
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
     * Ends {@code source} with an {@link UpstreamIdleException} when no item arrives for {@code idle}. A read bound is
     * kept apart from a total limit, which ends with a {@link TimeoutException}.
     */
    public static <T> Flux<T> endingWhenIdle(Flux<T> source, Duration idle) {
        return source.timeout(idle, Flux.error(UpstreamIdleException::new));
    }

    /** The upstream sent no byte for a read bound that a caller put on the body. */
    public static final class UpstreamIdleException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UpstreamIdleException() {
            super("The upstream sent nothing for its idle bound", null, false, false);
        }
    }

    /**
     * Whether a read bound ended the exchange: the client's read timeout, or {@link #endingWhenIdle}. Either way the
     * upstream sent no byte for that long. A total limit is not a read bound.
     */
    public static boolean readTimedOut(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ReadTimeoutException || cause instanceof UpstreamIdleException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ends {@code source} with a {@link TimeoutException} once {@code limit} has passed, counted from subscription. When it
     * fires, the timeout operator cancels the source itself, whether or not anything downstream is reading. Every
     * item's timer counts down to the same instant, so no item extends the limit.
     */
    private static <T> Flux<T> endingAfter(Flux<T> source, Duration limit) {
        return Flux.defer(() -> {
            var clock = Schedulers.parallel();
            long startedAt = clock.now(TimeUnit.NANOSECONDS);
            Mono<Long> rest = Mono.defer(() -> Mono.delay(
                    Duration.ofNanos(Math.max(0L, limit.toNanos() - (clock.now(TimeUnit.NANOSECONDS) - startedAt))),
                    clock));
            return source.timeout(rest, item -> rest);
        });
    }

    private static Part copyAndRelease(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return new Body(bytes);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    /** The time from now until {@code deadline}; zero once it has passed, never a fresh allowance. */
    public static Duration remainingUntil(Instant deadline) {
        Duration remaining = Duration.between(Instant.now(), deadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
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
        /** The stream ended with a typed timeout, such as the total stream deadline. */
        TIMED_OUT,
        /** The upstream sent no byte for a read bound, so the exchange was cut. */
        UPSTREAM_IDLE,
        STREAM_FAILED
    }
}
