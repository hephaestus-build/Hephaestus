package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Insert-once writes and the retention delete: no read lists a payload. */
public interface WorkPlaceHandoffRepository extends Repository<WorkPlaceHandoffRow, UUID> {

    /**
     * Writes the handoff of a sealed review, once: a second seal of the same review writes nothing.
     *
     * @return 1 when this call wrote the row, 0 when the review already has one
     */
    @Modifying
    @Query(value = """
            INSERT INTO work_place_handoff (id, workspace_id, review_id, job_id, item_id, item_version, producer,
                payload, payload_digest, expires_at, created_at)
            VALUES (:id, :workspaceId, :reviewId, :jobId, :itemId, :itemVersion, :producer,
                CAST(:payload AS jsonb), :payloadDigest, :expiresAt, :createdAt)
            ON CONFLICT (workspace_id, review_id) DO NOTHING
            """, nativeQuery = true)
    int enrol(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("reviewId") UUID reviewId,
            @Param("jobId") UUID jobId,
            @Param("itemId") long itemId,
            @Param("itemVersion") @Nullable String itemVersion,
            @Param("producer") String producer,
            @Param("payload") String payload,
            @Param("payloadDigest") String payloadDigest,
            @Param("expiresAt") Instant expiresAt,
            @Param("createdAt") Instant createdAt);

    /** Deletes handoffs past their expiry, oldest first. */
    @WorkspaceAgnostic("Cross-workspace retention sweep over job-scoped rows")
    @Modifying
    @Query(value = """
            DELETE FROM work_place_handoff WHERE id IN (
              SELECT h.id FROM work_place_handoff h WHERE h.expires_at < :now ORDER BY h.expires_at LIMIT :limit)
            """, nativeQuery = true)
    int deleteExpired(@Param("now") Instant now, @Param("limit") int limit);
}
