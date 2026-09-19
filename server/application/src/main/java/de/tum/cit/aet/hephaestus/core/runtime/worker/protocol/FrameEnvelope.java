package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

import java.util.UUID;

public record FrameEnvelope(int version, String frameId, WorkerControlFrame payload) {
    public static final int CURRENT_VERSION = 2;

    public FrameEnvelope {
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1, got: " + version);
        }
        if (frameId == null || frameId.isBlank()) {
            throw new IllegalArgumentException("frameId must not be blank");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
    }

    public static FrameEnvelope of(WorkerControlFrame payload) {
        return new FrameEnvelope(CURRENT_VERSION, UUID.randomUUID().toString(), payload);
    }
}
