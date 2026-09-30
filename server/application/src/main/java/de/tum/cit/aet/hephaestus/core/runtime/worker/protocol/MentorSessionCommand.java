package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Hub-to-worker session operation. The request id fences acknowledgements. */
public record MentorSessionCommand(UUID sessionId, UUID requestId, Operation operation, JsonNode body)
        implements WorkerControlFrame {
    public MentorSessionCommand {
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        java.util.Objects.requireNonNull(body, "body");
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(operation, "operation");
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
