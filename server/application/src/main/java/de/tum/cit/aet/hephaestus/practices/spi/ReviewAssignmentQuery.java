package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface ReviewAssignmentQuery {
    Optional<ReviewAssignmentSnapshot> find(long workspaceId, ReviewAssignmentId assignment);

    List<ReviewAssignmentId> executable(long workspaceId, UUID reviewId, long itemId);

    /**
     * Who a retained assignment was about, for history only: the item and the developer its review row names. Unlike
     * {@link #find}, it needs no executable practice version, so a retired practice still has its history.
     *
     * @param developerId the developer the review attributed the item to; {@code null} for a scan, or where the review
     *     attributed it to nobody
     */
    record Retained(
            ReviewAssignmentId id, ItemRef item, @Nullable Long developerId) {}

    /**
     * The retained assignments of one job of a review: those its frozen {@code ids} name or, when it recorded none, those
     * its review bound to {@code jobId}. Returns no assignments if any selected row or item is gone, a recorded key
     * cannot be read, or attribution is unreadable. A partial selection never stands for a complete run.
     */
    List<Retained> retained(long workspaceId, UUID reviewId, UUID jobId, Collection<ReviewAssignmentId> ids);

    /** One retained run to attribute: a job of a review and the assignments its input froze. */
    record RetainedRun(UUID reviewId, UUID jobId, Collection<ReviewAssignmentId> ids) {}

    /**
     * {@link #retained} for many runs of the workspace, keyed by job id, read in a fixed number of queries. A run
     * that {@link #retained} would leave unattributed has no assignments here either.
     */
    Map<UUID, List<Retained>> retained(long workspaceId, Collection<RetainedRun> runs);

    /** One review's reviewed item. */
    record ReviewedItem(UUID reviewId, long itemId) {}

    /**
     * A review's recorded state, and its assignments about one developer on one reviewed item. These are
     * recorded states, not a verdict on the work.
     *
     * @param stopReason why the review stopped; {@code null} where it recorded none
     * @param counts {@code null} where one of the item's assignments in the review cannot say whose work it was, so a
     *     count could miss the developer's own, or where none of them names the developer; also for a scan
     */
    record Recorded(
            ReviewStatus status,
            @Nullable ReviewStopReason stopReason,
            @Nullable AssignmentCounts counts) {}

    /** A developer's assignments on one reviewed item of one review, by recorded state. */
    record AssignmentCounts(long total, long judged, long reused, long withheld, long pending) {}

    /**
     * The state of each asked review and its assignments about {@code developerId} on the asked item, including those it
     * withheld before any job ran. Each pair is read as a pair: no other item of the review and no other review of the
     * item. A pair whose review is not in the workspace has no entry.
     */
    Map<ReviewedItem, Recorded> recorded(long workspaceId, long developerId, Collection<ReviewedItem> reviewed);
}
