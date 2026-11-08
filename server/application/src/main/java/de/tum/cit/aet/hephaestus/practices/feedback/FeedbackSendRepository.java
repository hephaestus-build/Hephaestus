package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public interface FeedbackSendRepository extends JpaRepository<FeedbackSend, UUID> {

    Optional<FeedbackSend> findByFeedbackIdAndWorkspaceId(UUID feedbackId, long workspaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM FeedbackSend s WHERE s.feedbackId = :feedbackId AND s.workspaceId =" + " :workspaceId")
    Optional<FeedbackSend> lockByFeedbackId(
            @Param("workspaceId") long workspaceId, @Param("feedbackId") UUID feedbackId);

    @WorkspaceAgnostic("Delivery recovery schedules tenant-owned sends for every workspace")
    @Query(value = """
        SELECT * FROM feedback_send WHERE (state IN ('PENDING','UNCERTAIN') AND next_attempt_at <= :now) OR (state='SENDING' AND lease_expires_at<=:now)
        ORDER BY next_attempt_at, feedback_id LIMIT :limit
        """, nativeQuery = true)
    List<FeedbackSend> findDue(@Param("now") Instant now, @Param("limit") int limit);

    @WorkspaceAgnostic("Send retry pressure is one instance-wide maximum; it reads no content or identifiers")
    @Query(value = """
        SELECT COALESCE(MAX(attempts), 0) FROM (
          SELECT attempts FROM feedback_send WHERE state IN ('PENDING','UNCERTAIN')
          UNION ALL
          SELECT attempts FROM feedback_send WHERE state = 'SENDING'
        ) active_sends
        """, nativeQuery = true)
    int maxAttemptsBeforeDone();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_send SET state = 'SENDING', lease_owner = :owner,
          lease_expires_at = :leaseUntil, attempts = attempts + 1, updated_at = :now
         WHERE workspace_id = :workspaceId AND feedback_id = :feedbackId
           AND ((state IN ('PENDING','UNCERTAIN') AND next_attempt_at <= :now) OR (state='SENDING' AND lease_expires_at<=:now))
        """, nativeQuery = true)
    int claim(
            @Param("workspaceId") long workspaceId,
            @Param("feedbackId") UUID feedbackId,
            @Param("owner") String owner,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_send SET state = :state, lease_owner = NULL, lease_expires_at = NULL,
          next_attempt_at = :nextAttemptAt, last_error = :error, updated_at = :now
         WHERE workspace_id = :workspaceId AND feedback_id = :feedbackId
           AND state = 'SENDING' AND lease_owner = :owner AND lease_expires_at > :now
        """, nativeQuery = true)
    int settle(
            @Param("workspaceId") long workspaceId,
            @Param("feedbackId") UUID feedbackId,
            @Param("owner") String owner,
            @Param("state") String state,
            @Param("now") Instant now,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("error") @Nullable String error);
}
