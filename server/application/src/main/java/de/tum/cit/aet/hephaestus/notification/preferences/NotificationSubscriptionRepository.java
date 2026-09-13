package de.tum.cit.aet.hephaestus.notification.preferences;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT s FROM NotificationSubscription s WHERE s.kind = 'PRODUCT_FEEDBACK' AND s.enabled = true "
                    + "AND s.frequency = 'DAILY' AND (s.lastDigestDate IS NULL OR s.lastDigestDate < :date) ORDER BY s.accountId")
    List<NotificationSubscription> pendingDigests(
            java.time.LocalDate date, org.springframework.data.domain.Pageable pageable);

    void deleteAllByAccountId(long accountId);
}
