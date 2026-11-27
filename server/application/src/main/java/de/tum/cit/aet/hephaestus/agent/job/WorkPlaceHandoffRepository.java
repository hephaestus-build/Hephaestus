package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Insert-once writes and the retention delete: no read lists a payload. */
public interface WorkPlaceHandoffRepository extends Repository<WorkPlaceHandoffRow, UUID> {

    WorkPlaceHandoffRow save(WorkPlaceHandoffRow handoff);

    /** Whether the review already handed its work place a copy: the seal reaches its listener once per review. */
    boolean existsByWorkspaceIdAndReviewId(long workspaceId, UUID reviewId);

    /** Deletes handoffs past their expiry, oldest first. */
    @WorkspaceAgnostic("Cross-workspace retention sweep over job-scoped rows")
    @Modifying
    @Query(value = """
            DELETE FROM work_place_handoff WHERE id IN (
              SELECT h.id FROM work_place_handoff h WHERE h.expires_at < :now ORDER BY h.expires_at LIMIT :limit)
            """, nativeQuery = true)
    int deleteExpired(@Param("now") Instant now, @Param("limit") int limit);
}
