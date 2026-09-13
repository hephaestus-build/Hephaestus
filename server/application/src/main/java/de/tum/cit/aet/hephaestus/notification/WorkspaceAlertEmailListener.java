package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationAttentionService;
import de.tum.cit.aet.hephaestus.notification.email.*;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionKind;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Delivery rechecks native account identity and current administration of the incident's workspace")
public class WorkspaceAlertEmailListener {
    private final IntegrationAttentionService attention;
    private final AccountWorkspaceMembershipQuery memberships;
    private final AccountContactQuery contacts;
    private final NotificationSubscriptionService subscriptions;
    private final EmailRenderer renderer;
    private final EmailGateway gateway;
    private final EmailUnsubscribeLinks unsubscribeLinks;
    private final EmailDeliveryMetrics metrics;
    private final Clock clock;

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(WorkspaceAlertEmailRequested event) {
        var change = event.change();
        if (!clock.instant().isBefore(change.occurredAt().plus(Duration.ofHours(24))) || !attention.isCurrent(change)) {
            metrics.record(EmailKind.WORKSPACE_ALERT, EmailDeliveryResult.Outcome.EXPIRED);
            return;
        }
        var token = subscriptions.unsubscribeToken(event.accountId(), NotificationSubscriptionKind.WORKSPACE_ALERTS);
        if (token.isEmpty()) {
            metrics.record(EmailKind.WORKSPACE_ALERT, EmailDeliveryResult.Outcome.UNSUBSCRIBED);
            return;
        }
        var recipient = contacts.activeVerifiedPrimaryEmail(event.accountId());
        var workspace = memberships.membershipsForAccount(event.accountId()).stream()
                .filter(membership -> membership.workspaceId() == change.workspaceId())
                .filter(membership -> "ADMIN".equals(membership.role()) || "OWNER".equals(membership.role()))
                .findFirst();
        if (recipient.isEmpty() || workspace.isEmpty()) {
            metrics.record(EmailKind.WORKSPACE_ALERT, EmailDeliveryResult.Outcome.NO_RECIPIENT);
            return;
        }
        String description = change.recovered()
                ? "The integration is connected again."
                : switch (change.problem()) {
                    case CREDENTIAL_REVOKED ->
                        "The provider revoked this integration's credentials. Reconnect it in workspace settings.";
                    case PROVIDER_SUSPENDED ->
                        "The provider suspended this integration. Restore it at the provider to resume synchronization.";
                };
        var rendered = renderer.render(
                EmailKind.WORKSPACE_ALERT,
                Map.of(
                        "integration",
                        change.kind().name(),
                        "description",
                        description,
                        "workspaceId",
                        change.workspaceId(),
                        "unsubscribeUrl",
                        unsubscribeLinks.confirmationUrl(token.get())));
        var result = gateway.send(new EmailMessage(
                EmailKind.WORKSPACE_ALERT,
                recipient.get(),
                rendered.subject(),
                rendered.text(),
                rendered.html(),
                unsubscribeLinks.url(token.get())));
        if (result.outcome().retryable()) {
            throw new NotificationDeliveryUnavailableException(EmailKind.WORKSPACE_ALERT, event.accountId());
        }
    }
}
