package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import io.micrometer.core.instrument.Counter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads JSONL frames from a runner's stdout. Frames are delimited by ASCII {@code \n} ONLY;
 * {@code \r} and U+2028/U+2029 are legal inside JSON string values and must survive framing —
 * {@link java.io.BufferedReader#readLine} would mis-split. Lines longer than {@code maxLineChars}
 * are dropped and terminate the pump. Every line that does not reach the sink as a frame is reported
 * through {@code onFrameLost}: the stream around it is no longer complete.
 */
final class JsonlStdoutPump {

    private static final Logger log = LoggerFactory.getLogger(JsonlStdoutPump.class);

    private final UUID sessionId;
    private final Reader source;
    private final ObjectMapper mapper;
    private final FrameSink sink;
    private final Runnable onFrameLost;
    private final IntConsumer onEof;
    private final IntSupplier exitCodeSupplier;
    private final Counter parseErrorCounter;
    private final int maxLineChars;
    private final Map<String, String> mdcSnapshot;

    JsonlStdoutPump(
            UUID sessionId,
            Reader source,
            ObjectMapper mapper,
            FrameSink sink,
            Runnable onFrameLost,
            IntConsumer onEof,
            IntSupplier exitCodeSupplier,
            Counter parseErrorCounter,
            int maxLineChars,
            Map<String, String> mdcSnapshot) {
        this.sessionId = sessionId;
        // 1 read() per byte against a raw stream is 1 syscall per byte; wrap unless already buffered.
        this.source = source instanceof BufferedReader br ? br : new BufferedReader(source);
        this.mapper = mapper;
        this.sink = sink;
        this.onFrameLost = onFrameLost;
        this.onEof = onEof;
        this.exitCodeSupplier = exitCodeSupplier;
        this.parseErrorCounter = parseErrorCounter;
        this.maxLineChars = maxLineChars;
        this.mdcSnapshot = mdcSnapshot;
    }

    void start() {
        Thread.ofVirtual()
                .name("mentor-pump-" + sessionId)
                .uncaughtExceptionHandler((t, ex) -> log.warn("Pump thread died unexpectedly: {}", sessionId, ex))
                .start(this::pumpLoop);
    }

    private void pumpLoop() {
        applyMdc();
        try {
            StringBuilder buf = new StringBuilder(1024);
            boolean discardRemainder = false;
            int ch;
            while (true) {
                try {
                    ch = source.read();
                } catch (IOException io) {
                    log.debug("Pump read threw — treating as EOF: sessionId={}, error={}", sessionId, io.getMessage());
                    break;
                }
                if (ch < 0) {
                    break;
                }
                if (ch == '\n') {
                    if (discardRemainder) {
                        parseErrorCounter.increment();
                        log.warn("Oversized JSONL line dropped: sessionId={}, cap={}", sessionId, maxLineChars);
                        frameLost();
                        return;
                    }
                    if (buf.length() == 0) {
                        continue;
                    }
                    handleLine(buf);
                    buf.setLength(0);
                    continue;
                }
                if (buf.length() >= maxLineChars) {
                    discardRemainder = true;
                    buf.setLength(0);
                    continue;
                }
                buf.append((char) ch);
            }
            if (discardRemainder) {
                // The line we were discarding never got its terminator — count it as a parse error
                // so dashboards see the cause even when the runner exits before flushing.
                parseErrorCounter.increment();
                log.warn("Oversized JSONL line at EOF (no terminator): sessionId={}, cap={}", sessionId, maxLineChars);
                frameLost();
            } else if (buf.length() > 0) {
                handleLine(buf);
            }
        } finally {
            int exitCode = exitCodeSupplier.getAsInt();
            try {
                onEof.accept(exitCode);
            } catch (Throwable t) {
                log.warn("onEof handler threw: sessionId={}", sessionId, t);
            }
            MDC.clear();
        }
    }

    private void handleLine(CharSequence line) {
        String text = line.toString();
        JsonNode frame;
        try {
            frame = mapper.readTree(text);
        } catch (JacksonException pe) {
            parseErrorCounter.increment();
            log.warn("Malformed JSONL frame: sessionId={}, len={}", sessionId, text.length());
            frameLost();
            return;
        }
        if (frame == null || frame.isMissingNode() || frame.isNull()) {
            parseErrorCounter.increment();
            frameLost();
            return;
        }
        int wireBytes = utf8Length(text) + 1; // + \n terminator
        try {
            sink.acceptFrame(frame, wireBytes);
        } catch (Throwable t) {
            log.warn("Frame sink threw — pump continues: sessionId={}", sessionId, t);
            frameLost();
        }
    }

    private void frameLost() {
        try {
            onFrameLost.run();
        } catch (RuntimeException e) {
            log.warn("onFrameLost handler threw: sessionId={}", sessionId, e);
        }
    }

    /** Exact UTF-8 byte length (RFC 3629). */
    static int utf8Length(CharSequence s) {
        int bytes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) bytes += 1;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    private void applyMdc() {
        if (mdcSnapshot != null) {
            for (var e : mdcSnapshot.entrySet()) {
                MDC.put(e.getKey(), e.getValue());
            }
        }
    }

    interface FrameSink {
        void acceptFrame(JsonNode frame, int wireBytes);
    }
}
