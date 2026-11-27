package de.tum.cit.aet.hephaestus.mentor.runtime;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** The sealed review events. Row-level security scopes each statement to the account bound to the transaction. */
interface MentorEventRepository extends Repository<MentorEvent, UUID> {
    /**
     * A second seal of the review waits for the first one's commit on the review key, then writes nothing.
     *
     * @return 1 when this call wrote the review's event, 0 when the review has one
     */
    @Modifying
    @Query(value = """
            INSERT INTO mentor_event (id, account_id, kind, disposition, workspace_id, review_id, job_id,
              admission_digest, occurred_at, received_at)
            VALUES (:id, :accountId, 'REVIEW_SEALED', 'PENDING', :workspaceId, :reviewId, :jobId, :digest, :sealedAt,
              :receivedAt)
            ON CONFLICT (review_id, workspace_id) DO NOTHING
            """, nativeQuery = true)
    int insertReviewSealed(
            @Param("id") UUID id,
            @Param("accountId") long accountId,
            @Param("workspaceId") long workspaceId,
            @Param("reviewId") UUID reviewId,
            @Param("jobId") UUID jobId,
            @Param("digest") String admissionDigest,
            @Param("sealedAt") Instant sealedAt,
            @Param("receivedAt") Instant receivedAt);

    /** The review's event, when the bound account owns it. */
    @Query(value = """
            SELECT CAST(id AS text) FROM mentor_event
            WHERE workspace_id = :workspaceId AND review_id = :reviewId AND account_id = :accountId
              AND job_id = :jobId AND admission_digest = :digest
            """, nativeQuery = true)
    Optional<String> findReviewSealed(
            @Param("workspaceId") long workspaceId,
            @Param("reviewId") UUID reviewId,
            @Param("accountId") long accountId,
            @Param("jobId") UUID jobId,
            @Param("digest") String admissionDigest);
}
