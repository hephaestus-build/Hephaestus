package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ProductFeedbackRepository extends JpaRepository<ProductFeedback, UUID> {
    /** Serializes the account's quota check and insert across every server and workspace. Reads no rows. */
    @WorkspaceAgnostic("Account-wide submission lock; reads no workspace data")
    @Query(
            value =
                    "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended('product-feedback-submit:' || CAST(:accountId AS text), 0))) locked",
            nativeQuery = true)
    int lockSubmission(@Param("accountId") long accountId);

    @WorkspaceAgnostic("The daily report limit counts the account's own reports across workspaces and none")
    long countByAccountIdAndCreatedAtGreaterThanEqual(long accountId, Instant since);

    void deleteAllByWorkspaceId(Long workspaceId);

    void deleteAllByAccountId(long accountId);

    /** One slice of every report. The slices partition them: a resolved report is closed, whatever its filing says. */
    @WorkspaceAgnostic("Instance administrators read product feedback from every workspace and from none")
    @Query(value = """
                    SELECT p.* FROM product_feedback p LEFT JOIN product_feedback_filing f ON f.feedback_id = p.id
                    WHERE :status = 'ALL'
                       OR (:status = 'RESOLVED'
                           AND (p.resolved_at IS NOT NULL OR (f.state = 'FILED' AND f.issue_open = false)))
                       OR (p.resolved_at IS NULL AND (
                           (:status = 'OPEN' AND (f.feedback_id IS NULL OR f.state IN ('SAVED', 'STOPPED')))
                           OR (:status = 'IN_FLIGHT' AND f.state IN
                               ('DRAFT_QUEUED', 'DRAFTING', 'ANSWERED', 'HELD', 'QUEUED', 'FILING'))
                           OR (:status = 'ON_GITHUB' AND f.state = 'FILED' AND f.issue_open IS DISTINCT FROM false)))
                    ORDER BY CASE WHEN :status = 'RESOLVED' THEN COALESCE(p.resolved_at, p.created_at)
                             ELSE p.created_at END DESC
                    """, countQuery = """
                    SELECT count(*) FROM product_feedback p LEFT JOIN product_feedback_filing f ON f.feedback_id = p.id
                    WHERE :status = 'ALL'
                       OR (:status = 'RESOLVED'
                           AND (p.resolved_at IS NOT NULL OR (f.state = 'FILED' AND f.issue_open = false)))
                       OR (p.resolved_at IS NULL AND (
                           (:status = 'OPEN' AND (f.feedback_id IS NULL OR f.state IN ('SAVED', 'STOPPED')))
                           OR (:status = 'IN_FLIGHT' AND f.state IN
                               ('DRAFT_QUEUED', 'DRAFTING', 'ANSWERED', 'HELD', 'QUEUED', 'FILING'))
                           OR (:status = 'ON_GITHUB' AND f.state = 'FILED' AND f.issue_open IS DISTINCT FROM false)))
                    """, nativeQuery = true)
    Page<ProductFeedback> findForAdmin(@Param("status") String status, Pageable pageable);

    List<ProductFeedback> findAllByAccountIdOrderByCreatedAtDesc(long accountId);

    List<ProductFeedback> findAllByAccountId(long accountId);
}
