package de.tum.cit.aet.hephaestus.notification.preferences;

import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestSubscriptions;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The weekly digest choice is one account-level subscription, {@link NotificationSubscriptionKind#MENTOR_DIGEST}.
 * Not gated on a runtime role: the webhook role answers the Slack note's Turn off button.
 */
@Component
@RequiredArgsConstructor
@WorkspaceAgnostic("Subscriptions belong to native accounts, not workspaces")
class WeeklyDigestSubscriptionAdapter implements WeeklyDigestSubscriptions {

    private final NotificationSubscriptionRepository subscriptions;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<Long> subscribedAccountIds() {
        return subscriptions.subscribedAccountIds(NotificationSubscriptionKind.MENTOR_DIGEST);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> enabledSince(long accountId) {
        return subscriptions
                .findByAccountIdAndKind(accountId, NotificationSubscriptionKind.MENTOR_DIGEST)
                .filter(NotificationSubscription::isEnabled)
                .flatMap(row -> Optional.ofNullable(row.getEnabledSince()));
    }

    /** Off stays off; turning it on again happens in User settings. */
    @Override
    @Transactional
    public void turnOff(long accountId) {
        subscriptions.findForUpdate(accountId).stream()
                .filter(row -> row.getKind() == NotificationSubscriptionKind.MENTOR_DIGEST)
                .forEach(row -> row.setEnabled(false, clock.instant()));
    }
}
