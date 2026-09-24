package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

/** Hub handshake reply. {@code sessionId} changes on reconnect and fences correlated Git operations. */
public record WorkerWelcome(int negotiatedVersion, String sessionId) implements WorkerControlFrame {
    public WorkerWelcome {
        if (negotiatedVersion < 1) {
            throw new IllegalArgumentException("negotiatedVersion must be >= 1, got: " + negotiatedVersion);
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }
}
