package de.tum.cit.aet.hephaestus.practices.review.setup;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewAudience;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface ReviewSetupRepository extends Repository<ReviewSetup, UUID> {

    /** What a review request reads of its setup. */
    record SetupForRequest(
            long workspaceId,
            String itemKind,
            ReviewAudience audience,
            @Nullable BigDecimal budgetUsd,
            boolean enabled,
            @Nullable Instant removedAt) {}

    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.practices.review.setup.ReviewSetupRepository$SetupForRequest(
               s.workspaceId, s.itemKind, s.audience, s.budgetUsd, s.enabled, s.removedAt)
          FROM ReviewSetup s
         WHERE s.id = :setupId AND s.workspaceId = :workspaceId
        """)
    Optional<SetupForRequest> findForRequest(@Param("workspaceId") long workspaceId, @Param("setupId") UUID setupId);

    @Query("""
        SELECT p.practiceId FROM ReviewSetupPractice p
         WHERE p.setupId = :setupId AND p.workspaceId = :workspaceId
         ORDER BY p.practiceId
        """)
    List<Long> findPracticeIds(@Param("workspaceId") long workspaceId, @Param("setupId") UUID setupId);
}
