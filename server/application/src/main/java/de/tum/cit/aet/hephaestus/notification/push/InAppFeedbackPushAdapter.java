package de.tum.cit.aet.hephaestus.notification.push;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.practices.spi.InAppFeedbackPreparedListener;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * Queues a push notification for each registered device of the developer that practice feedback now
 * waits for. It runs in the transaction that prepared the feedback, so the notification exists exactly
 * when the feedback does, and it only writes: whether the notification may still go out is decided when
 * it is sent. Present in every runtime role, because feedback can be prepared wherever a review finishes.
 */
@Component
@WorkspaceAgnostic("Resolves the recipient's account; the queued rows carry the workspace")
class InAppFeedbackPushAdapter implements InAppFeedbackPreparedListener {

    private final PushNotificationRepository notificationRepository;
    private final AccountWorkspaceMembershipQuery memberships;
    private final Clock clock;

    InAppFeedbackPushAdapter(
            PushNotificationRepository notificationRepository,
            AccountWorkspaceMembershipQuery memberships,
            Clock clock) {
        this.notificationRepository = notificationRepository;
        this.memberships = memberships;
        this.clock = clock;
    }

    @Override
    public void inAppFeedbackPrepared(long workspaceId, long recipientUserId) {
        memberships.activeAccountIdForMember(workspaceId, recipientUserId).ifPresent(accountId -> {
            Instant now = clock.instant();
            notificationRepository.enqueueForAccount(
                    workspaceId,
                    recipientUserId,
                    accountId,
                    PushNotification.Kind.PRACTICE_FEEDBACK.name(),
                    now.truncatedTo(ChronoUnit.HOURS),
                    now);
        });
    }
}
