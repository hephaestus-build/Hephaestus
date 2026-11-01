package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("GitHub locators left after erasure; instance-wide, with no workspace")
interface ProductFeedbackCleanupRepository extends JpaRepository<ProductFeedbackCleanup, UUID> {
    @Query(
            value = "SELECT id FROM product_feedback_cleanup WHERE next_attempt_at <= :now"
                    + " ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<UUID> claimable(@Param("now") Instant now);

    List<ProductFeedbackCleanup> findAllByAttemptsGreaterThanEqualOrderByCreatedAt(int attempts);
}
