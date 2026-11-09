package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery.Role;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationAttentionService;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import de.tum.cit.aet.hephaestus.notification.email.EmailMessage;
import de.tum.cit.aet.hephaestus.notification.email.EmailNotice.Prepared;
import de.tum.cit.aet.hephaestus.notification.email.EmailRenderer;
import de.tum.cit.aet.hephaestus.notification.email.EmailTestSupport;
import de.tum.cit.aet.hephaestus.notification.email.EmailUnsubscribeLinks;
import de.tum.cit.aet.hephaestus.notification.email.Emails;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionKind;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorkspaceAlertEmailListenerTest extends BaseUnitTest {
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    private final IntegrationAttentionService attention = mock(IntegrationAttentionService.class);
    private final AccountMembershipQuery memberships = mock(AccountMembershipQuery.class);
    private final AccountContactQuery contacts = mock(AccountContactQuery.class);
    private final NotificationSubscriptionService subscriptions = mock(NotificationSubscriptionService.class);
    private final EmailRenderer renderer = EmailTestSupport.renderer();
    private final Emails emails = mock(Emails.class);
    private final EmailUnsubscribeLinks links = mock(EmailUnsubscribeLinks.class);
    private final WorkspaceAlertEmailListener listener = new WorkspaceAlertEmailListener(
            attention, memberships, contacts, subscriptions, renderer, emails, links, Clock.fixed(NOW, ZoneOffset.UTC));
    private final IntegrationAttentionChangedEvent change = new IntegrationAttentionChangedEvent(
            3L, 7L, IntegrationKind.SLACK, IntegrationAttentionChangedEvent.Problem.CREDENTIAL_REVOKED, false, 1L, NOW);

    @Test
    void shouldSuppressAnIncidentSupersededByRecovery() {
        assertThat(listener.prepare(new WorkspaceAlertEmailRequested(change, 42L)))
                .isEqualTo(Prepared.withhold(Outcome.EXPIRED));
        verifyNoInteractions(contacts, subscriptions);
    }

    @Test
    void shouldHonorOptOutBeforeDelivery() {
        when(attention.isCurrent(change)).thenReturn(true);
        assertThat(listener.prepare(new WorkspaceAlertEmailRequested(change, 42L)))
                .isEqualTo(Prepared.withhold(Outcome.UNSUBSCRIBED));
    }

    @Test
    void shouldNotSendToAnAdministratorOfOnlyADifferentWorkspace() {
        when(attention.isCurrent(change)).thenReturn(true);
        when(subscriptions.unsubscribeToken(42L, NotificationSubscriptionKind.WORKSPACE_ALERTS, NOW))
                .thenReturn(Optional.of("token"));
        when(contacts.activeVerifiedPrimaryEmail(42L)).thenReturn(Optional.of("admin@example.org"));
        when(memberships.membershipsForAccount(42L))
                .thenReturn(List.of(new AccountMembershipQuery.MembershipView(8L, "other", "Other", Role.ADMIN)));
        assertThat(listener.prepare(new WorkspaceAlertEmailRequested(change, 42L)))
                .isEqualTo(Prepared.withhold(Outcome.NO_RECIPIENT));
    }

    @Test
    void shouldSendToTheCurrentSubscribedWorkspaceOwnerWithUnsubscribeHeaders() {
        when(attention.isCurrent(change)).thenReturn(true);
        when(subscriptions.unsubscribeToken(42L, NotificationSubscriptionKind.WORKSPACE_ALERTS, NOW))
                .thenReturn(Optional.of("token"));
        when(contacts.activeVerifiedPrimaryEmail(42L)).thenReturn(Optional.of("admin@example.org"));
        when(memberships.membershipsForAccount(42L))
                .thenReturn(List.of(new AccountMembershipQuery.MembershipView(7L, "owned", "Owned", Role.OWNER)));
        when(links.url("token")).thenReturn("https://example.org/unsubscribe/token");
        when(links.confirmationUrl("token")).thenReturn("https://example.org/unsubscribe/token/confirm");
        when(emails.configured()).thenReturn(true);
        EmailMessage message = sent(listener.prepare(new WorkspaceAlertEmailRequested(change, 42L)));
        assertThat(message.to()).isEqualTo("admin@example.org");
        assertThat(message.unsubscribeUrl()).isEqualTo("https://example.org/unsubscribe/token");
        assertThat(message.text())
                .contains("Slack in workspace Owned", "https://hephaestus.example/w/owned/admin/settings");
        assertThat(message.html())
                .contains("Slack", ">Owned</span>", "href=\"https://hephaestus.example/w/owned/admin/settings\"");
    }

    @ParameterizedTest
    @CsvSource({
        "CREDENTIAL_EXPIRING,false,expires soon",
        "CREDENTIAL_REVOKED,false,credentials",
        "CREDENTIAL_EXPIRING,true,connected again"
    })
    void shouldSendGitLabCredentialAlertsToSubscribedWorkspaceAdministrators(
            IntegrationAttentionChangedEvent.Problem problem, boolean recovered, String description) {
        var event = new IntegrationAttentionChangedEvent(3L, 7L, IntegrationKind.GITLAB, problem, recovered, 1L, NOW);
        when(attention.isCurrent(event)).thenReturn(true);
        when(subscriptions.unsubscribeToken(42L, NotificationSubscriptionKind.WORKSPACE_ALERTS, NOW))
                .thenReturn(Optional.of("token"));
        when(contacts.activeVerifiedPrimaryEmail(42L)).thenReturn(Optional.of("admin@example.org"));
        when(memberships.membershipsForAccount(42L))
                .thenReturn(List.of(new AccountMembershipQuery.MembershipView(7L, "owned", "Owned", Role.ADMIN)));
        when(links.url("token")).thenReturn("https://example.org/unsubscribe/token");
        when(links.confirmationUrl("token")).thenReturn("https://example.org/unsubscribe/token/confirm");
        when(emails.configured()).thenReturn(true);
        EmailMessage message = sent(listener.prepare(new WorkspaceAlertEmailRequested(event, 42L)));
        assertThat(message.text()).contains("GitLab", description).doesNotContain("glpat-");
    }

    private static EmailMessage sent(Prepared prepared) {
        assertThat(prepared).isInstanceOf(Prepared.Send.class);
        return ((Prepared.Send) prepared).message();
    }
}
