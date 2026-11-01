package de.tum.cit.aet.hephaestus.notification.preferences;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

@WorkspaceAgnostic(
        "Email subscriptions belong to native accounts across workspaces; query keys are account, kind or opaque unsubscribe token")
interface NotificationSubscriptionRepository extends JpaRepository<NotificationSubscription, UUID> {
    List<NotificationSubscription> findAllByAccountIdOrderByKind(long accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM NotificationSubscription s WHERE s.accountId = :accountId ORDER BY s.kind")
    List<NotificationSubscription> findForUpdate(long accountId);

    Optional<NotificationSubscription> findByAccountIdAndKind(long accountId, NotificationSubscriptionKind kind);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<NotificationSubscription> findByUnsubscribeToken(UUID unsubscribeToken);

    @Query("SELECT s.accountId FROM NotificationSubscription s WHERE s.kind = :kind AND s.enabled = true")
    List<Long> subscribedAccountIds(NotificationSubscriptionKind kind);

    @Modifying
    @Query(value = """
            INSERT INTO notification_subscription
                (id, account_id, kind, enabled, enabled_since, unsubscribe_token, version)
            VALUES (gen_random_uuid(), :accountId, 'PRODUCT_FEEDBACK_UPDATES', true,
                    TIMESTAMPTZ 'epoch', gen_random_uuid(), 0)
            ON CONFLICT (account_id, kind) DO NOTHING
            """, nativeQuery = true)
    void insertDefaultProductFeedbackUpdates(long accountId);

    void deleteAllByAccountId(long accountId);
}
