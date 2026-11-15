package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ResultHistoryQuery {
    List<ResultHistoryEntry> forDeveloper(long workspaceId, long developerAccountId, Instant since, int limit);

    List<ResultHistoryEntry> forItem(long workspaceId, long itemId, long practiceId, int limit);

    Optional<ResultHistoryEntry> find(long workspaceId, UUID resultId, long viewerAccountId, SourceUsePurpose purpose);

    /** {@link #find} of many results in one read, keyed by result id; a result the viewer may not read is absent. */
    Map<UUID, ResultHistoryEntry> findAll(
            long workspaceId, Collection<UUID> resultIds, long viewerAccountId, SourceUsePurpose purpose);

    /** The result one problem belongs to, as {@link #find} reads it for the viewer; empty when the viewer may not. */
    Optional<ResultHistoryEntry> findOfProblem(
            long workspaceId, UUID problemId, long viewerAccountId, SourceUsePurpose purpose);

    List<ResultHistoryEntry> forAssignment(long workspaceId, ReviewAssignmentId assignment, int limit);
}
