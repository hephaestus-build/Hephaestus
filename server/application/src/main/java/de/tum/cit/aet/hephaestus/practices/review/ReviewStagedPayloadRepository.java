package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewStagedPayload;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface ReviewStagedPayloadRepository extends Repository<ReviewStagedPayload, ReviewItem.Id> {
    @Query(
            value = "SELECT staged_payload::text FROM review_item WHERE workspace_id=:workspaceId"
                    + " AND review_id=:reviewId AND practice_id=:practiceId AND item_id=:itemId",
            nativeQuery = true)
    Optional<String> findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
            long workspaceId, UUID reviewId, long practiceId, long itemId);

    @WorkspaceAgnostic("Bounded staging retention joins each membership to its owning job and workspace")
    @Modifying
    @Query(value = """
            WITH expired AS (
                SELECT m.workspace_id,m.review_id,m.practice_id,m.item_id,
                    j.status NOT IN ('QUEUED','RUNNING') AS terminal,
                    CASE WHEN j.status='CANCELLED' THEN 'CANCELLED'
                        WHEN j.status='TIMED_OUT' THEN 'TIMED_OUT'
                        WHEN j.status NOT IN ('QUEUED','RUNNING') THEN 'EXECUTION_INCOMPLETE'
                        ELSE 'COPY_AUTHORITY_UNAVAILABLE' END AS stop_reason FROM review_item m
                JOIN review_job j ON j.id=m.job_id AND j.workspace_id=m.workspace_id
                WHERE m.staged_payload IS NOT NULL AND
                    (j.status NOT IN ('QUEUED','RUNNING') OR (m.staged_payload->'evidence'->>'expiresAt')::timestamptz <= :now)
                LIMIT :limit FOR UPDATE OF m SKIP LOCKED
            )
            UPDATE review_item m SET
                staged_payload=CASE WHEN e.terminal THEN NULL ELSE jsonb_build_object(
                    'digest',m.staged_payload->>'digest','candidate',NULL,'evidence','{}'::jsonb,'reason',e.stop_reason) END,
                staged_attempt=CASE WHEN e.terminal THEN NULL ELSE m.staged_attempt END,
                state=CASE WHEN m.state='STAGED' THEN 'WITHHELD' ELSE m.state END,
                reason=CASE WHEN m.state='STAGED' THEN e.stop_reason ELSE m.reason END
            FROM expired e WHERE m.workspace_id=e.workspace_id AND m.review_id=e.review_id
                AND m.practice_id=e.practice_id AND m.item_id=e.item_id
            """, nativeQuery = true)
    int discardUnusable(Instant now, int limit);

    @Modifying
    @Query(
            value = "UPDATE review_item SET staged_payload=CAST(:payload AS jsonb) WHERE workspace_id=:workspaceId"
                    + " AND review_id=:reviewId AND practice_id=:practiceId AND item_id=:itemId",
            nativeQuery = true)
    int write(long workspaceId, UUID reviewId, long practiceId, long itemId, @Nullable String payload);
}
