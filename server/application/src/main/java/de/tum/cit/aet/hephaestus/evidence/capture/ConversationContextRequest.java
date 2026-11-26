package de.tum.cit.aet.hephaestus.evidence.capture;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

public record ConversationContextRequest(
        long workspaceId,
        long developerId,
        UUID threadId,
        @Nullable UUID stimulusEntryId,
        BiConsumer<String, String> outlineSource) {
    public ConversationContextRequest(long workspaceId, long developerId, UUID threadId, @Nullable UUID stimulusEntryId) {
        this(workspaceId, developerId, threadId, stimulusEntryId, ConversationContextRequest::ignoreSource);
    }

    private static void ignoreSource(String origin, String team) {}

    public ConversationContextRequest {
        Objects.requireNonNull(threadId, "threadId");
        if (workspaceId <= 0 || developerId <= 0) {
            throw new IllegalArgumentException("Workspace and developer ids must be positive");
        }
    }
}
