package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery.Role;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationAttentionService;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryMetrics;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult;
import de.tum.cit.aet.hephaestus.notification.email.EmailGateway;
import de.tum.cit.aet.hephaestus.notification.email.EmailKind;
import de.tum.cit.aet.hephaestus.notification.email.EmailMessage;
import de.tum.cit.aet.hephaestus.notification.email.EmailRenderer;
import de.tum.cit.aet.hephaestus.notification.email.EmailUnsubscribeLinks;
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
    private final AccountMembershipQuery memberships;
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
        var token = subscriptions.unsubscribeToken(
                event.accountId(), NotificationSubscriptionKind.WORKSPACE_ALERTS, change.occurredAt());
        if (token.isEmpty()) {
            metrics.record(EmailKind.WORKSPACE_ALERT, EmailDeliveryResult.Outcome.UNSUBSCRIBED);
            return;
        }
        var recipient = contacts.activeVerifiedPrimaryEmail(event.accountId());
        var workspace = memberships.membershipsForAccount(event.accountId()).stream()
                .filter(membership -> membership.workspaceId() == change.workspaceId())
                .filter(membership -> membership.role() == Role.ADMIN || membership.role() == Role.OWNER)
                .findFirst();
        if (recipient.isEmpty() || workspace.isEmpty()) {
            metrics.record(EmailKind.WORKSPACE_ALERT, EmailDeliveryResult.Outcome.NO_RECIPIENT);
            return;
        }
        String description = change.recovered()
                ? "The integration is connected again."
                : switch (change.problem()) {
                    case CREDENTIAL_EXPIRING ->
                        "The GitLab token expires soon and Hephaestus could not rotate it. Replace it in workspace settings before it expires.";
                    case CREDENTIAL_REVOKED ->
                        "The provider revoked this integration’s credentials. Reconnect it in workspace settings.";
                    case PROVIDER_SUSPENDED ->
                        "The provider suspended this integration. Restore it at the provider to start the sync again.";
                    case PROVIDER_CLEANUP_UNCONFIRMED ->
                        switch (change.kind()) {
                            case GITHUB ->
                                "We could not confirm that GitHub removed the app from the disconnected organization. Uninstall the Hephaestus app in the organization’s GitHub settings.";
                            case GITLAB ->
                                "We could not confirm that GitLab removed the webhook of the disconnected group. Delete the Hephaestus webhook in the group’s GitLab settings, and revoke the token you gave Hephaestus.";
                            case SLACK ->
                                "We could not confirm that Slack revoked the token of the disconnected workspace. It may still work. Remove the Hephaestus app in your Slack workspace’s app settings.";
                            case OUTLINE ->
                                "We could not confirm that Outline removed the webhook of the disconnected workspace. Delete the Hephaestus webhook in Outline’s settings, and revoke the API key you gave Hephaestus.";
                        };
                };
        if (!gateway.configured()) {
            metrics.record(EmailKind.WORKSPACE_ALERT, EmailDeliveryResult.Outcome.NOT_CONFIGURED);
            return;
        }
        var rendered = renderer.render(
                EmailKind.WORKSPACE_ALERT,
                Map.of(
                        "integration",
                        switch (change.kind()) {
                            case GITHUB -> "GitHub";
                            case GITLAB -> "GitLab";
                            case SLACK -> "Slack";
                            case OUTLINE -> "Outline";
                        },
                        "description",
                        description,
                        "workspaceSlug",
                        workspace.get().workspaceSlug(),
                        "workspaceName",
                        workspace.get().workspaceName(),
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
