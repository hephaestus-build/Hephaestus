package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Explicit, job-scoped access only: no listing, so no queue or admin read can carry a payload. */
public interface ReviewCompositionBasisRepository extends Repository<ReviewCompositionBasis, UUID> {

    ReviewCompositionBasis save(ReviewCompositionBasis basis);

    Optional<ReviewCompositionBasis> findByWorkspaceIdAndJobId(long workspaceId, UUID jobId);

    @Modifying
    @Query("DELETE FROM ReviewCompositionBasis b WHERE b.workspaceId = :workspaceId AND b.jobId = :jobId")
    int deleteForJob(@Param("workspaceId") long workspaceId, @Param("jobId") UUID jobId);

    /**
     * Removes expired capsules and ended recovery inputs. Pending delivery and a tool trace retain the capsule
     * within its ordinary expiry; neither extends that expiry.
     */
    @WorkspaceAgnostic("Cross-workspace retention sweep over job-scoped rows")
    @Modifying
    @Query(value = """
        DELETE FROM review_composition_basis WHERE job_id IN (
          SELECT b.job_id FROM review_composition_basis b
          JOIN review_job j ON j.workspace_id = b.workspace_id AND j.id = b.job_id
          WHERE b.expires_at < :now OR (j.status NOT IN ('QUEUED', 'RUNNING') AND NOT jsonb_exists(b.payload,'tools')
            AND NOT EXISTS(SELECT 1 FROM feedback f WHERE f.workspace_id=j.workspace_id AND f.review_id=j.review_id
              AND f.destination='ON_WORK' AND f.state IN ('READY','AWAITING_APPROVAL','DELIVERED','UNCONFIRMED')
              AND NOT jsonb_exists(f.composition,'stop')))
          LIMIT :limit)
        """, nativeQuery = true)
    int deleteUnusable(@Param("now") Instant now, @Param("limit") int limit);
}
