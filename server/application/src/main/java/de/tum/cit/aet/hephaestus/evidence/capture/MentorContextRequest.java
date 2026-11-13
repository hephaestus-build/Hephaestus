package de.tum.cit.aet.hephaestus.evidence.capture;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record MentorContextRequest(
        long workspaceId,
        long developerId,
        UUID threadId,
        @Nullable UUID stimulusEntryId) {
    public MentorContextRequest {
        Objects.requireNonNull(threadId, "threadId");
        if (workspaceId <= 0 || developerId <= 0) {
            throw new IllegalArgumentException("Workspace and developer ids must be positive");
        }
    }
}
