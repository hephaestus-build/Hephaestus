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

    /** Whether the review already handed its work place a copy: another job of the review sealed it. */
    boolean existsByWorkspaceIdAndReviewId(long workspaceId, UUID reviewId);

    /**
     * Writes the review's one handoff, or nothing when a seal of the same review already did. A racing seal waits for
     * the first one's commit and then commits too, instead of failing on it. The clause names no conflict target: the id
     * derives from the review, so the primary key and the review key both say that the review has its handoff, and a
     * row of an earlier release has a random id under the same review key.
     *
     * @return 1 when this seal wrote the handoff
     */
    @Modifying
    @Query(value = """
            INSERT INTO work_place_handoff (id, workspace_id, review_id, job_id, item_id, item_version, producer,
              payload, payload_digest, expires_at, created_at)
            VALUES (:#{#h.id}, :#{#h.workspaceId}, :#{#h.reviewId}, :#{#h.jobId}, :#{#h.itemId},
              CAST(:#{#h.itemVersion} AS VARCHAR), :#{#h.producer.name()}, CAST(:#{#h.payload.toString()} AS JSONB),
              :#{#h.payloadDigest}, :#{#h.expiresAt}, :#{#h.createdAt})
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertOnce(@Param("h") WorkPlaceHandoffRow handoff);

    /** Deletes handoffs past their expiry, oldest first. */
    @WorkspaceAgnostic("Cross-workspace retention sweep over job-scoped rows")
    @Modifying
    @Query(value = """
            DELETE FROM work_place_handoff WHERE id IN (
              SELECT h.id FROM work_place_handoff h WHERE h.expires_at < :now ORDER BY h.expires_at LIMIT :limit)
            """, nativeQuery = true)
    int deleteExpired(@Param("now") Instant now, @Param("limit") int limit);
}
