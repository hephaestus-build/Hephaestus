package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationAttentionService;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.notification.email.*;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionKind;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkspaceAlertEmailListenerTest extends BaseUnitTest {
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    private final IntegrationAttentionService attention = mock(IntegrationAttentionService.class);
    private final AccountWorkspaceMembershipQuery memberships = mock(AccountWorkspaceMembershipQuery.class);
    private final AccountContactQuery contacts = mock(AccountContactQuery.class);
    private final NotificationSubscriptionService subscriptions = mock(NotificationSubscriptionService.class);
    private final EmailRenderer renderer = EmailTestSupport.renderer();
    private final EmailGateway gateway = mock(EmailGateway.class);
    private final EmailUnsubscribeLinks links = mock(EmailUnsubscribeLinks.class);
    private final EmailDeliveryMetrics metrics = mock(EmailDeliveryMetrics.class);
    private final WorkspaceAlertEmailListener listener = new WorkspaceAlertEmailListener(
            attention,
            memberships,
            contacts,
            subscriptions,
            renderer,
            gateway,
            links,
            metrics,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final IntegrationAttentionChangedEvent change = new IntegrationAttentionChangedEvent(
            3L, 7L, IntegrationKind.SLACK, IntegrationAttentionChangedEvent.Problem.CREDENTIAL_REVOKED, false, 1L, NOW);

    @Test
    void shouldSuppressAnIncidentSupersededByRecovery() {
        listener.on(new WorkspaceAlertEmailRequested(change, 42L));
        verify(metrics).record(EmailKind.WORKSPACE_ALERT, EmailDeliveryResult.Outcome.EXPIRED);
        verifyNoInteractions(gateway, contacts, subscriptions);
    }

    @Test
    void shouldHonorOptOutBeforeDelivery() {
        when(attention.isCurrent(change)).thenReturn(true);
        listener.on(new WorkspaceAlertEmailRequested(change, 42L));
        verify(metrics).record(EmailKind.WORKSPACE_ALERT, EmailDeliveryResult.Outcome.UNSUBSCRIBED);
        verifyNoInteractions(gateway);
    }

    @Test
    void shouldNotSendToAnAdministratorOfOnlyADifferentWorkspace() {
        when(attention.isCurrent(change)).thenReturn(true);
        when(subscriptions.unsubscribeToken(42L, NotificationSubscriptionKind.WORKSPACE_ALERTS, NOW))
                .thenReturn(Optional.of("token"));
        when(contacts.activeVerifiedPrimaryEmail(42L)).thenReturn(Optional.of("admin@example.org"));
        when(memberships.membershipsForAccount(42L))
                .thenReturn(List.of(new AccountWorkspaceMembershipQuery.WorkspaceMembershipView(
                        8L, "other", "Other", "ADMIN", 501L)));
        listener.on(new WorkspaceAlertEmailRequested(change, 42L));
        verify(metrics).record(EmailKind.WORKSPACE_ALERT, EmailDeliveryResult.Outcome.NO_RECIPIENT);
        verifyNoInteractions(gateway);
    }

    @Test
    void shouldSendToTheCurrentSubscribedWorkspaceOwnerWithUnsubscribeHeaders() {
        when(attention.isCurrent(change)).thenReturn(true);
        when(subscriptions.unsubscribeToken(42L, NotificationSubscriptionKind.WORKSPACE_ALERTS, NOW))
                .thenReturn(Optional.of("token"));
        when(contacts.activeVerifiedPrimaryEmail(42L)).thenReturn(Optional.of("admin@example.org"));
        when(memberships.membershipsForAccount(42L))
                .thenReturn(List.of(new AccountWorkspaceMembershipQuery.WorkspaceMembershipView(
                        7L, "owned", "Owned", "OWNER", 501L)));
        when(links.url("token")).thenReturn("https://example.org/unsubscribe/token");
        when(links.confirmationUrl("token")).thenReturn("https://example.org/unsubscribe/token/confirm");
        when(gateway.send(any())).thenReturn(EmailDeliveryResult.sent("id"));
        listener.on(new WorkspaceAlertEmailRequested(change, 42L));
        var sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(gateway).send(sent.capture());
        EmailMessage message = sent.getValue();
        assertThat(message.to()).isEqualTo("admin@example.org");
        assertThat(message.unsubscribeUrl()).isEqualTo("https://example.org/unsubscribe/token");
        assertThat(message.text())
                .contains("Slack in workspace Owned", "https://hephaestus.example/w/owned/admin/settings");
        assertThat(message.html())
                .contains("Slack", ">Owned</span>", "href=\"https://hephaestus.example/w/owned/admin/settings\"");
    }
}
