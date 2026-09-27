package de.tum.cit.aet.hephaestus.notification.push;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@WorkspaceAgnostic("Tenant-scoped writes carry workspace_id; the sender and receipt sweeps deliberately scan the fleet")
public interface PushNotificationRepository extends JpaRepository<PushNotification, UUID> {

    /**
     * Queues one notification per registered device of {@code accountId}, unless that device already has
     * one for this workspace and window. One statement, inside the transaction that prepared the feedback.
     */
    @Transactional
    @Modifying
    @Query(value = """
        INSERT INTO push_notification (
            id, workspace_id, recipient_user_id, push_device_id, kind, window_start,
            state, attempts, next_attempt_at, created_at, version
        ) SELECT
            gen_random_uuid(), :workspaceId, :recipientUserId, d.id, :kind, :windowStart,
            'PENDING', 0, :now, :now, 0
          FROM push_device d
         WHERE d.account_id = :accountId
        ON CONFLICT (push_device_id, workspace_id, kind, window_start) DO NOTHING
        """, nativeQuery = true)
    int enqueueForAccount(
            @Param("workspaceId") Long workspaceId,
            @Param("recipientUserId") Long recipientUserId,
            @Param("accountId") Long accountId,
            @Param("kind") String kind,
            @Param("windowStart") Instant windowStart,
            @Param("now") Instant now);

    /** Due rows: queued or released, or claimed by a sender whose claim has lapsed. */
    @Query("""
        SELECT n FROM PushNotification n
         WHERE n.nextAttemptAt <= :now
           AND n.state IN (de.tum.cit.aet.hephaestus.notification.push.PushNotification.State.PENDING,
                           de.tum.cit.aet.hephaestus.notification.push.PushNotification.State.SENDING)
         ORDER BY n.nextAttemptAt ASC
        """)
    List<PushNotification> findDue(@Param("now") Instant now, Pageable pageable);

    /** Claims a due row until {@code leaseUntil}; 1 for exactly one sender however many race for it. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE push_notification
           SET state = 'SENDING', attempts = attempts + 1, next_attempt_at = :leaseUntil, version = version + 1
         WHERE id = :id AND workspace_id = :workspaceId AND version = :version
           AND state IN ('PENDING', 'SENDING')
           AND next_attempt_at <= :now
        """, nativeQuery = true)
    int claim(
            @Param("id") UUID id,
            @Param("workspaceId") Long workspaceId,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("version") long version);

    /** Sent rows whose receipts Expo should have by now (it recommends waiting about 15 minutes). */
    @Query("""
        SELECT n FROM PushNotification n
         WHERE n.state = de.tum.cit.aet.hephaestus.notification.push.PushNotification.State.SENT
           AND n.sentAt <= :sentBefore
         ORDER BY n.sentAt ASC
        """)
    List<PushNotification> findAwaitingReceipt(@Param("sentBefore") Instant sentBefore, Pageable pageable);

    @Transactional
    @Modifying
    @Query("DELETE FROM PushNotification n WHERE n.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);

    @Transactional
    @Modifying
    @Query("DELETE FROM PushNotification n WHERE n.workspaceId = :workspaceId")
    int deleteByWorkspaceId(@Param("workspaceId") Long workspaceId);

    @Transactional
    @Modifying
    @Query("DELETE FROM PushNotification n WHERE n.pushDeviceId = :deviceId")
    int deleteByPushDeviceId(@Param("deviceId") UUID deviceId);
}
