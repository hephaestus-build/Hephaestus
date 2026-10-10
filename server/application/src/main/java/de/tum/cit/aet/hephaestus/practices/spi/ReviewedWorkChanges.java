package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Capture comparison without a dependency from practices to the job-owning module. */
public interface ReviewedWorkChanges {
    /**
     * Among runs the caller has already authorized, those whose captured head, title or description differs.
     * Unknown capture provenance does not establish a change. Runs outside the workspace are excluded.
     */
    Set<UUID> materiallyChanged(long workspaceId, Collection<UUID> runIds, PullRequestRevision current);

    /** The authorized observation ids whose verified closing-issue file differs from its current projection. */
    Set<UUID> materiallyChangedLinkedIssues(
            long workspaceId, Collection<ObservationEvidence> observations, long pullRequestId);

    record ObservationEvidence(UUID observationId, UUID runId, JsonNode citations) {}

    boolean linkedCaptureCurrent(long workspaceId, UUID jobId, long pullRequestId, String signalRevision);

    /**
     * The head and title-and-description revision a run captured of exactly this pull request, when its stored
     * identity and manifest are readable, its core and pinned change were available, and its source contract permits
     * both for delivery. Anything else is empty: an identity that cannot be read is unknown, never equal.
     */
    Optional<CapturedIdentity> deliverableCapture(long workspaceId, UUID jobId, long pullRequestId);

    record CapturedIdentity(String head, String titleAndDescriptionRevision) {}

    /** The mirrored material fields, not an integration entity or an event delivery identity. */
    record PullRequestRevision(
            long artifactId,
            @Nullable String head,
            @Nullable String title,
            @Nullable String body) {}
}
