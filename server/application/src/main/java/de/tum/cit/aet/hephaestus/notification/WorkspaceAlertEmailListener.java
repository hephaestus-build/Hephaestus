package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery.Role;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationAttentionService;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import de.tum.cit.aet.hephaestus.notification.email.EmailKind;
import de.tum.cit.aet.hephaestus.notification.email.EmailMessage;
import de.tum.cit.aet.hephaestus.notification.email.EmailNotice;
import de.tum.cit.aet.hephaestus.notification.email.EmailRenderer;
import de.tum.cit.aet.hephaestus.notification.email.EmailUnsubscribeLinks;
import de.tum.cit.aet.hephaestus.notification.email.Emails;
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
public class WorkspaceAlertEmailListener implements EmailNotice<WorkspaceAlertEmailRequested> {
    static final Duration EXPIRES_AFTER = Duration.ofHours(24);

    private final IntegrationAttentionService attention;
    private final AccountMembershipQuery memberships;
    private final AccountContactQuery contacts;
    private final NotificationSubscriptionService subscriptions;
    private final EmailRenderer renderer;
    private final Emails emails;
    private final EmailUnsubscribeLinks unsubscribeLinks;
    private final Clock clock;

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(WorkspaceAlertEmailRequested event) {
        emails.enqueue(this, event);
    }

    @Override
    public EmailKind kind() {
        return EmailKind.WORKSPACE_ALERT;
    }

    @Override
    public Class<WorkspaceAlertEmailRequested> eventType() {
        return WorkspaceAlertEmailRequested.class;
    }

    @Override
    public Address address(WorkspaceAlertEmailRequested event) {
        var change = event.change();
        return new Address(
                change.connectionId() + ":" + change.revision() + ":" + event.accountId(),
                event.accountId(),
                change.workspaceId(),
                change.occurredAt().plus(EXPIRES_AFTER));
    }

    @Override
    public Prepared prepare(WorkspaceAlertEmailRequested event) {
        var change = event.change();
        if (!clock.instant().isBefore(change.occurredAt().plus(EXPIRES_AFTER)) || !attention.isCurrent(change)) {
            return Prepared.withhold(Outcome.EXPIRED);
        }
        var token = subscriptions.unsubscribeToken(
                event.accountId(), NotificationSubscriptionKind.WORKSPACE_ALERTS, change.occurredAt());
        if (token.isEmpty()) {
            return Prepared.withhold(Outcome.UNSUBSCRIBED);
        }
        var recipient = contacts.activeVerifiedPrimaryEmail(event.accountId());
        var workspace = memberships.membershipsForAccount(event.accountId()).stream()
                .filter(membership -> membership.workspaceId() == change.workspaceId())
                .filter(membership -> membership.role() == Role.ADMIN || membership.role() == Role.OWNER)
                .findFirst();
        if (recipient.isEmpty() || workspace.isEmpty()) {
            return Prepared.withhold(Outcome.NO_RECIPIENT);
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
                                "We could not confirm that GitHub removed the app from the disconnected account. Uninstall the Hephaestus app in the GitHub settings of that organization or personal account.";
                            case GITLAB ->
                                "We could not confirm that GitLab removed the webhook of the disconnected group. Delete the Hephaestus webhook in the group’s GitLab settings, and revoke the token you gave Hephaestus.";
                            case SLACK ->
                                "We could not confirm that Slack revoked the token of the disconnected workspace. It may still work. Remove the Hephaestus app in your Slack workspace’s app settings.";
                            case OUTLINE ->
                                "We could not confirm that Outline removed the webhook of the disconnected workspace. Delete the Hephaestus webhook in Outline’s settings, and revoke the API key you gave Hephaestus.";
                        };
                };
        if (!emails.configured()) {
            return Prepared.withhold(Outcome.NOT_CONFIGURED);
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
        return Prepared.send(new EmailMessage(
                EmailKind.WORKSPACE_ALERT,
                recipient.get(),
                rendered.subject(),
                rendered.text(),
                rendered.html(),
                unsubscribeLinks.url(token.get())));
    }
}
