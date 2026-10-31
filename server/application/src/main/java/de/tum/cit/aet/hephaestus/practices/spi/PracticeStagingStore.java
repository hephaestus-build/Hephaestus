package de.tum.cit.aet.hephaestus.practices.spi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Private verified candidates, retained on the invocation's memberships until its single seal. */
public interface PracticeStagingStore {
    record StagedPractice(
            ReviewAssignmentId assignment,
            int attempt,
            String digest,
            @Nullable ResultCandidate candidate,
            JsonNode evidence,
            @Nullable String reason) {}

    Optional<StagedPractice> receipt(long workspaceId, UUID reviewId, UUID jobId, ReviewAssignmentId assignment);

    List<StagedPractice> staged(long workspaceId, UUID reviewId, UUID jobId);

    boolean workChanged(long workspaceId, UUID reviewId, UUID jobId);

    int discardUnusable(Instant now, int limit);

    void refuseCopy(long workspaceId, UUID jobId, int attempt, ReviewAssignmentId assignment);

    void stage(long workspaceId, UUID jobId, StagedPractice submission);
}
