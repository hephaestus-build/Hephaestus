package de.tum.cit.aet.hephaestus.evidence.capture;

import de.tum.cit.aet.hephaestus.core.privacy.spi.OutlinePersonCopyScope;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

public record ConversationContextRequest(
        long workspaceId,
        long developerId,
        UUID threadId,
        @Nullable UUID stimulusEntryId,
        Consumer<OutlinePersonCopyScope.Source> outlineSource) {
    private static final Consumer<OutlinePersonCopyScope.Source> IGNORE_SOURCE = source -> {};

    public ConversationContextRequest(long workspaceId, long developerId, UUID threadId, @Nullable UUID stimulusEntryId) {
        this(workspaceId, developerId, threadId, stimulusEntryId, IGNORE_SOURCE);
    }

    public ConversationContextRequest {
        Objects.requireNonNull(threadId, "threadId");
        if (workspaceId <= 0 || developerId <= 0) {
            throw new IllegalArgumentException("Workspace and developer ids must be positive");
        }
    }
}
