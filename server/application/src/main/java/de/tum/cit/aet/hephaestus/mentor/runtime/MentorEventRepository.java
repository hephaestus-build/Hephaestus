package de.tum.cit.aet.hephaestus.mentor.runtime;

import de.tum.cit.aet.hephaestus.mentor.spi.MentorReviewIntake.SealedReview;
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
            VALUES (:id, :#{#s.accountId()}, 'REVIEW_SEALED', 'PENDING', :#{#s.workspaceId()}, :#{#s.reviewId()},
              :#{#s.jobId()}, :#{#s.admissionDigest()}, :#{#s.sealedAt()}, :receivedAt)
            ON CONFLICT (review_id, workspace_id) DO NOTHING
            """, nativeQuery = true)
    int insertReviewSealed(
            @Param("id") UUID id, @Param("s") SealedReview sealed, @Param("receivedAt") Instant receivedAt);

    /** The review's event, when the bound account owns it for the same job and admission. */
    @Query(value = """
            SELECT CAST(id AS text) FROM mentor_event
            WHERE workspace_id = :#{#s.workspaceId()} AND review_id = :#{#s.reviewId()}
              AND account_id = :#{#s.accountId()} AND job_id = :#{#s.jobId()}
              AND admission_digest = :#{#s.admissionDigest()}
            """, nativeQuery = true)
    Optional<String> findReviewSealed(@Param("s") SealedReview sealed);
}
