package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ProductFeedbackRepository extends JpaRepository<ProductFeedback, UUID> {
    void deleteAllByWorkspaceId(Long workspaceId);

    void deleteAllByAccountId(long accountId);

    /** The reports the admins still work on here: those not on GitHub, because none was filed or filing stopped. */
    @WorkspaceAgnostic("Instance administrators read product feedback from every workspace and from none")
    @Query(value = """
                    SELECT p.* FROM product_feedback p LEFT JOIN product_feedback_filing f ON f.feedback_id = p.id
                    WHERE (f.feedback_id IS NULL OR f.state IN ('EMAILED', 'STOPPED'))
                      AND (:status = 'ALL' OR (:status = 'OPEN' AND p.resolved_at IS NULL)
                           OR (:status = 'RESOLVED' AND p.resolved_at IS NOT NULL))
                    ORDER BY CASE WHEN :status = 'RESOLVED' THEN p.resolved_at ELSE p.created_at END DESC
                    """, countQuery = """
                    SELECT count(*) FROM product_feedback p LEFT JOIN product_feedback_filing f ON f.feedback_id = p.id
                    WHERE (f.feedback_id IS NULL OR f.state IN ('EMAILED', 'STOPPED'))
                      AND (:status = 'ALL' OR (:status = 'OPEN' AND p.resolved_at IS NULL)
                           OR (:status = 'RESOLVED' AND p.resolved_at IS NOT NULL))
                    """, nativeQuery = true)
    Page<ProductFeedback> findNotOnGitHub(@Param("status") String status, Pageable pageable);

    List<ProductFeedback> findAllByAccountIdOrderByCreatedAtDesc(long accountId);

    List<ProductFeedback> findAllByAccountId(long accountId);
}
