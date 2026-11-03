package de.tum.cit.aet.hephaestus.practices.review.schedule;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchPauseReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewBatchRepository extends JpaRepository<ReviewBatch, UUID> {
    boolean existsByWorkspaceIdAndScheduleId(long workspaceId, UUID scheduleId);

    Page<ReviewBatch> findByWorkspaceId(long workspaceId, Pageable pageable);

    /** Batches still under way first, then the newest, so that a running batch is never past the first page. */
    @Query("""
            SELECT b FROM ReviewBatch b
            WHERE b.workspaceId = :workspaceId AND (:origin IS NULL OR b.origin = :origin)
            ORDER BY CASE WHEN b.status IN (
                    de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchStatus.AWAITING_CONFIRMATION,
                    de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchStatus.RUNNING,
                    de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchStatus.PAUSED)
                THEN 0 ELSE 1 END, b.createdAt DESC, b.id
            """)
    Page<ReviewBatch> pageOf(
            @Param("workspaceId") long workspaceId,
            @Param("origin") @Nullable ReviewBatchOrigin origin,
            Pageable pageable);

    Optional<ReviewBatch> findByIdAndWorkspaceId(UUID id, long workspaceId);

    List<ReviewBatch> findByWorkspaceIdOrderByCreatedAtDesc(long workspaceId, Pageable pageable);

    boolean existsByWorkspaceIdAndStatusIn(long workspaceId, Collection<ReviewBatchStatus> statuses);

    @WorkspaceAgnostic("The driver rechecks budget pauses across workspaces")
    List<ReviewBatch> findByStatusAndPauseReasonOrderByUpdatedAtAscIdAsc(
            ReviewBatchStatus status, ReviewBatchPauseReason reason, Pageable pageable);

    @WorkspaceAgnostic("The driver reads active batches across workspaces")
    List<ReviewBatch> findByStatusOrderByUpdatedAtAscIdAsc(ReviewBatchStatus status, Pageable pageable);
}
