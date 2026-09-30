package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Worker-to-hub acknowledgement, runner frame or terminal session event. */
public record MentorSessionEvent(UUID sessionId, @Nullable UUID requestId, Kind kind, JsonNode body)
        implements WorkerControlFrame {
    public MentorSessionEvent {
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        java.util.Objects.requireNonNull(body, "body");
        java.util.Objects.requireNonNull(kind, "kind");
        if (!body.isObject()) throw new IllegalArgumentException("Mentor session payload must be an object");
    }

    public enum Kind {
        ACK,
        FRAME,
        BUSY,
        FAILED,
        CLOSED
    }
}
