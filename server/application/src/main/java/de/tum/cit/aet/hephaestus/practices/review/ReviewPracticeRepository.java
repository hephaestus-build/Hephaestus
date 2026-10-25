package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.review.model.ReviewPractice;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewPracticeRepository extends JpaRepository<ReviewPractice, ReviewPractice.Id> {
    Optional<ReviewPractice> findByWorkspaceIdAndReviewIdAndPracticeId(
            long workspaceId, UUID reviewId, long practiceId);

    List<ReviewPractice> findByWorkspaceIdAndReviewIdOrderByPracticeIdAsc(long workspaceId, UUID reviewId);

    List<ReviewPractice> findByWorkspaceIdAndReviewIdInOrderByReviewIdAscPracticeIdAsc(
            long workspaceId, Collection<UUID> reviewIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ReviewPractice p WHERE p.workspaceId = :workspaceId AND p.reviewId = :reviewId ORDER BY"
            + " p.practiceId")
    List<ReviewPractice> lockAssignments(@Param("workspaceId") long workspaceId, @Param("reviewId") UUID reviewId);
}
