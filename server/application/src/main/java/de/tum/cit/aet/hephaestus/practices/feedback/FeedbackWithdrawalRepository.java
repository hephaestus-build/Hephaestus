package de.tum.cit.aet.hephaestus.practices.feedback;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface FeedbackWithdrawalRepository extends JpaRepository<FeedbackWithdrawal, UUID> {

    @Query("""
        SELECT w FROM FeedbackWithdrawal w
        WHERE w.workspaceId = :workspaceId AND w.feedbackId = :feedbackId AND w.restoredAt IS NULL
        """)
    Optional<FeedbackWithdrawal> findActive(
            @Param("workspaceId") Long workspaceId, @Param("feedbackId") UUID feedbackId);

    /** The open withdrawals among {@code feedbackIds}. Callers guard an empty {@code feedbackIds}. */
    @Query("""
        SELECT w FROM FeedbackWithdrawal w
        WHERE w.workspaceId = :workspaceId AND w.feedbackId IN :feedbackIds AND w.restoredAt IS NULL
        """)
    List<FeedbackWithdrawal> findActiveFor(
            @Param("workspaceId") Long workspaceId, @Param("feedbackIds") Collection<UUID> feedbackIds);

    /** Which of {@code feedbackIds} are withdrawn now. */
    default Set<UUID> withdrawnAmong(long workspaceId, Collection<UUID> feedbackIds) {
        return feedbackIds.isEmpty()
                ? Set.of()
                : findActiveFor(workspaceId, feedbackIds).stream()
                        .map(FeedbackWithdrawal::getFeedbackId)
                        .collect(Collectors.toSet());
    }

    @Query("""
        SELECT w FROM FeedbackWithdrawal w
        WHERE w.workspaceId = :workspaceId AND w.feedbackId = :feedbackId
        ORDER BY w.withdrawnAt DESC, w.id DESC
        """)
    List<FeedbackWithdrawal> findHistory(@Param("workspaceId") Long workspaceId, @Param("feedbackId") UUID feedbackId);
}
