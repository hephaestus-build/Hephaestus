package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionKind;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Each incident's explicit workspace determines its current administrator recipients")
public class WorkspaceAlertEmailPreparation {
    private final AccountWorkspaceMembershipQuery memberships;
    private final NotificationSubscriptionService subscriptions;
    private final ApplicationEventPublisher events;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(IntegrationAttentionChangedEvent event) {
        for (long accountId : memberships.administratorAccountIds(event.workspaceId())) {
            if (subscriptions.isEnabled(accountId, NotificationSubscriptionKind.WORKSPACE_ALERTS)) {
                events.publishEvent(new WorkspaceAlertEmailRequested(event, accountId));
            }
        }
    }
}
