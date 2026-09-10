package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

import java.io.Serial;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Encodes / decodes {@link FrameEnvelope} as JSON text frames. Frames larger than
 * {@link #MAX_FRAME_BYTES} fail — the hub closes such connections with WS code 1009.
 */
public final class FrameCodec {

    /** 256 KiB; mentor stdin/stdout is split into separate frames so the cap is per-frame. */
    public static final int MAX_FRAME_BYTES = 256 * 1024;

    private final ObjectMapper objectMapper;

    public FrameCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(FrameEnvelope envelope) {
        try {
            String json = objectMapper.writeValueAsString(envelope);
            if (json.length() > MAX_FRAME_BYTES) {
                throw new FrameCodecException(
                        "encoded frame exceeds " + MAX_FRAME_BYTES + " bytes (got " + json.length() + ")");
            }
            return json;
        } catch (JacksonException e) {
            throw new FrameCodecException("failed to encode frame", e);
        }
    }

    public FrameEnvelope decode(String json) {
        if (json == null) {
            throw new FrameCodecException("input must not be null");
        }
        if (json.length() > MAX_FRAME_BYTES) {
            throw new FrameCodecException(
                    "incoming frame exceeds " + MAX_FRAME_BYTES + " bytes (got " + json.length() + ")");
        }
        try {
            return objectMapper.readValue(json, FrameEnvelope.class);
        } catch (JacksonException e) {
            throw new FrameCodecException("failed to decode frame", e);
        }
    }

    public static final class FrameCodecException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        public FrameCodecException(String message) {
            super(message);
        }

        public FrameCodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
