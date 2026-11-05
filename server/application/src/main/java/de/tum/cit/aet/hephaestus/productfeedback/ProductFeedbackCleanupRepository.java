package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("GitHub locators left after erasure; instance-wide, with no workspace")
interface ProductFeedbackCleanupRepository extends JpaRepository<ProductFeedbackCleanup, UUID> {
    @Query(
            value = "SELECT id FROM product_feedback_cleanup WHERE manual_since IS NULL AND next_attempt_at <= :now"
                    + " ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<UUID> claimable(@Param("now") Instant now);

    /** Waiting steps no email has announced yet, locked so that one announce takes each. */
    @Query(
            value = "SELECT id FROM product_feedback_cleanup WHERE manual_since IS NOT NULL AND notified_at IS NULL"
                    + " ORDER BY manual_since FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<UUID> unannounced();

    @Modifying
    @Query("UPDATE ProductFeedbackCleanup c SET c.notifiedAt = :now WHERE c.id IN :ids")
    void markAnnounced(@Param("ids") List<UUID> ids, @Param("now") Instant now);

    /** The steps that wait for a maintainer, newest first, as Needs action lists them. */
    List<ProductFeedbackCleanup> findTop100ByManualSinceNotNullOrderByManualSinceDesc();

    long countByManualSinceNotNull();

    Optional<ProductFeedbackCleanup> findByIdAndManualSinceNotNull(UUID id);

    List<ProductFeedbackCleanup> findAllByIdInAndManualSinceNotNullOrderByRepositoryAscIssueNumberAsc(List<UUID> ids);

    /** Public issues that automatic cleanup may still move into the private repository. */
    long countByManualSinceIsNullAndPublicIssueTrue();
}
