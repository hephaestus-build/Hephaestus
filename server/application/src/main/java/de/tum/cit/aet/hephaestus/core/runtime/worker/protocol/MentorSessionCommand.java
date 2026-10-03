package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Hub-to-worker session operation. The request id fences acknowledgements. */
public record MentorSessionCommand(UUID sessionId, UUID requestId, Operation operation, JsonNode body)
        implements WorkerControlFrame {
    public MentorSessionCommand {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(operation, "operation");
        if (!body.isObject()) throw new IllegalArgumentException("Mentor session payload must be an object");
    }

    public enum Operation {
        OPEN,
        SEND,
        BIND_TURN,
        UNBIND_TURN,
        CLOSE
    }
}
