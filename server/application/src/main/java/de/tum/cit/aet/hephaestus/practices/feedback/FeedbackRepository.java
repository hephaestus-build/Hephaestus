package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackChecks;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Transactional
public interface FeedbackRepository extends JpaRepository<Feedback, UUID>, JpaSpecificationExecutor<Feedback> {

    Optional<Feedback> findByIdAndWorkspaceId(UUID id, long workspaceId);

    List<Feedback> findByWorkspaceIdAndReviewIdOrderByCreatedAtAscIdAsc(long workspaceId, UUID reviewId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Feedback f WHERE f.id = :id AND f.workspaceId = :workspaceId")
    Optional<Feedback> lockByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") long workspaceId);

    /** Locks the rows in one id order, so two readers locking overlapping sets cannot deadlock. */
    @Query(value = """
        SELECT f.* FROM feedback f WHERE f.workspace_id = :workspaceId AND f.id IN (:ids)
        ORDER BY f.id FOR UPDATE
        """, nativeQuery = true)
    List<Feedback> lockAllByIdInAndWorkspaceId(
            @Param("ids") Collection<UUID> ids, @Param("workspaceId") long workspaceId);

    /** Serializes an edit's receipt handoff with withdrawal and other revision writes in one UUID order. */
    @Query(value = """
        WITH RECURSIVE family AS (
          SELECT f.id,f.replaces_id,f.destination,f.composition FROM feedback f WHERE f.workspace_id=:workspaceId AND f.id=:id
          UNION ALL
          SELECT prior.id,prior.replaces_id,prior.destination,prior.composition FROM family child
            JOIN feedback prior ON prior.id=child.replaces_id AND prior.workspace_id=:workspaceId
          WHERE child.destination='ON_WORK' AND child.composition->>'editOf'=child.replaces_id::text)
        SELECT f.id FROM feedback f JOIN family member ON member.id=f.id
        WHERE f.workspace_id=:workspaceId ORDER BY f.id FOR UPDATE OF f
        """, nativeQuery = true)
    List<UUID> lockCorrectionFamily(@Param("workspaceId") long workspaceId, @Param("id") UUID id);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Feedback f SET f.composition=:composition WHERE f.workspaceId=:workspaceId AND f.id=:id")
    int updateComposition(
            @Param("workspaceId") long workspaceId, @Param("id") UUID id, @Param("composition") JsonNode composition);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Feedback f SET f.state = :state, f.stopReason = :reason,
            f.deliveredAt = :deliveredAt WHERE f.id = :id AND f.workspaceId = :workspaceId
        """)
    int updateDelivery(
            @Param("workspaceId") long workspaceId,
            @Param("id") UUID id,
            @Param("state") FeedbackState state,
            @Param("reason") @Nullable FeedbackStopReason reason,
            @Param("deliveredAt") @Nullable Instant deliveredAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Feedback f SET f.state = :delivered, f.deliveredAt = :readAt
         WHERE f.id = :id AND f.workspaceId = :workspaceId AND f.developerId = :developerId
           AND f.destination = :destination AND f.state = :ready AND f.deliveredAt IS NULL
        """)
    int recordFirstRead(
            @Param("workspaceId") long workspaceId,
            @Param("id") UUID id,
            @Param("developerId") long developerId,
            @Param("readAt") Instant readAt,
            @Param("destination") FeedbackDestination destination,
            @Param("ready") FeedbackState ready,
            @Param("delivered") FeedbackState delivered);

    @Query(value = """
        SELECT f.* FROM feedback f WHERE f.workspace_id = :workspaceId AND f.developer_id IN (:developerIds)
          AND f.destination = 'PRACTICE_PAGE' AND f.state IN ('READY','DELIVERED')
        ORDER BY f.created_at DESC, f.id DESC
        """, nativeQuery = true)
    List<Feedback> findReadablePracticePage(
            @Param("workspaceId") long workspaceId, @Param("developerIds") Collection<Long> developerIds);

    @Query(value = """
        SELECT f.* FROM feedback f WHERE f.workspace_id = :workspaceId AND f.developer_id = :developerId
          AND f.destination = 'PRACTICE_PAGE' AND f.state IN ('READY','DELIVERED')
          AND EXISTS(SELECT 1 FROM feedback_basis b JOIN result r ON r.id = b.result_id AND r.workspace_id = b.workspace_id
            JOIN practice p ON p.id = r.practice_id AND p.workspace_id = r.workspace_id
            WHERE b.feedback_id = f.id AND b.workspace_id = f.workspace_id AND p.slug = :practiceSlug)
        ORDER BY f.created_at DESC, f.id DESC LIMIT 1
        """, nativeQuery = true)
    List<Feedback> findLatestPracticePage(
            @Param("workspaceId") long workspaceId,
            @Param("developerId") long developerId,
            @Param("practiceSlug") String practiceSlug);

    @WorkspaceAgnostic("Verified account absorption moves the same person across all workspaces")
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Feedback f SET f.developerId = :claimedAccountId WHERE f.developerId =" + " :unclaimedAccountId")
    int absorbDeveloper(
            @Param("claimedAccountId") long claimedAccountId, @Param("unclaimedAccountId") long unclaimedAccountId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Feedback f SET f.checks = :checks WHERE f.id = :id AND f.workspaceId =" + " :workspaceId")
    int updateChecks(
            @Param("workspaceId") long workspaceId,
            @Param("id") UUID id,
            @Param("checks") Map<FeedbackCheckStage, FeedbackChecks> checks);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Feedback f SET f.replacesId = :previous WHERE f.id = :id AND f.workspaceId =" + " :workspaceId")
    int recordReplacement(
            @Param("workspaceId") long workspaceId, @Param("id") UUID id, @Param("previous") UUID previous);

    @Query(value = """
        SELECT f.* FROM feedback f WHERE f.workspace_id=:workspaceId AND f.item_id=:itemId AND f.developer_id=:developerId
         AND f.destination='ON_WORK' AND EXISTS(SELECT 1 FROM feedback_copy c WHERE c.feedback_id=f.id AND c.workspace_id=f.workspace_id
           AND c.kind='SUMMARY' AND c.state='POSTED' AND c.posted_at IS NOT NULL)
         ORDER BY f.delivered_at DESC NULLS LAST,f.created_at DESC,f.id::text DESC LIMIT 1
        """, nativeQuery = true)
    Optional<Feedback> findLastConfirmedSummary(
            @Param("workspaceId") long workspaceId,
            @Param("itemId") long itemId,
            @Param("developerId") long developerId);

    /** Whether another feedback of the review already has a posted copy, on the work or in a conversation. */
    @Query(value = """
        SELECT EXISTS(SELECT 1 FROM feedback WHERE workspace_id=:workspaceId AND review_id=:reviewId AND id<>:id
          AND destination<>'PRACTICE_PAGE' AND delivered_at IS NOT NULL)
        """, nativeQuery = true)
    boolean hasOtherPostedFeedback(
            @Param("workspaceId") long workspaceId, @Param("reviewId") UUID reviewId, @Param("id") UUID id);

    /** Seconds since the oldest feedback on the work that may be sent and has no posted copy, or null when none waits. */
    @WorkspaceAgnostic("Delivery freshness is one instance-wide age; it reads no content")
    @Query(value = """
        SELECT EXTRACT(EPOCH FROM now()-min(created_at)) FROM feedback
         WHERE destination='ON_WORK' AND state IN ('READY','UNCONFIRMED') AND delivered_at IS NULL
        """, nativeQuery = true)
    @Nullable
    Double oldestWaitingOnWorkSeconds();

    @Query(value = """
        SELECT f.* FROM feedback f WHERE f.workspace_id=:workspaceId AND f.destination='CONVERSATION' AND f.state='READY' AND f.created_at<:cutoff
          AND NOT EXISTS(SELECT 1 FROM feedback_copy c WHERE c.workspace_id=f.workspace_id AND c.feedback_id=f.id AND (c.write_started_at IS NOT NULL OR c.posted_at IS NOT NULL))
        """, nativeQuery = true)
    List<Feedback> findExpiredConversation(@Param("workspaceId") long workspaceId, @Param("cutoff") Instant cutoff);

    @WorkspaceAgnostic("System-wide expiry discovers workspaces; every mutation remains tenant-scoped")
    @Query(value = """
        SELECT DISTINCT f.workspace_id FROM feedback f WHERE f.destination='CONVERSATION' AND f.state='READY' AND f.created_at<:cutoff
          AND NOT EXISTS(SELECT 1 FROM feedback_copy c WHERE c.workspace_id=f.workspace_id AND c.feedback_id=f.id AND (c.write_started_at IS NOT NULL OR c.posted_at IS NOT NULL))
        """, nativeQuery = true)
    List<Long> expiredConversationWorkspaces(@Param("cutoff") Instant cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback SET state='WITHHELD',stop_reason='CONVERSATION_EXPIRED' WHERE workspace_id=:workspaceId AND id=:id
          AND destination='CONVERSATION' AND state='READY' AND created_at<:cutoff
          AND NOT EXISTS(SELECT 1 FROM feedback_copy c WHERE c.workspace_id=feedback.workspace_id AND c.feedback_id=feedback.id
            AND (c.write_started_at IS NOT NULL OR c.posted_at IS NOT NULL))
        """, nativeQuery = true)
    int expireConversation(
            @Param("workspaceId") long workspaceId, @Param("id") UUID id, @Param("cutoff") Instant cutoff);
}
