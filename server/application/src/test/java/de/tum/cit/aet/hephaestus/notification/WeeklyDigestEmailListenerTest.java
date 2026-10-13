package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestPointer;
import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestWritten;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryMetrics;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult;
import de.tum.cit.aet.hephaestus.notification.email.EmailGateway;
import de.tum.cit.aet.hephaestus.notification.email.EmailKind;
import de.tum.cit.aet.hephaestus.notification.email.EmailMessage;
import de.tum.cit.aet.hephaestus.notification.email.EmailTestSupport;
import de.tum.cit.aet.hephaestus.notification.email.EmailUnsubscribeLinks;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionKind;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WeeklyDigestEmailListenerTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-10-12T07:05:00Z");
    private static final long ACCOUNT_ID = 42L;
    private static final UUID ENTRY_ID = UUID.fromString("0f8a6c1e-3b0d-4a43-9f43-3bd1d5a0e2c4");

    private final NotificationSubscriptionService subscriptions = mock(NotificationSubscriptionService.class);
    private final AccountContactQuery contacts = mock(AccountContactQuery.class);
    private final AccountWorkspaceMembershipQuery memberships = mock(AccountWorkspaceMembershipQuery.class);
    private final EmailGateway gateway = mock(EmailGateway.class);
    private final EmailUnsubscribeLinks links = mock(EmailUnsubscribeLinks.class);
    private final EmailDeliveryMetrics metrics = mock(EmailDeliveryMetrics.class);
    private final WeeklyDigestEmailListener listener = new WeeklyDigestEmailListener(
            subscriptions,
            contacts,
            memberships,
            EmailTestSupport.renderer(),
            gateway,
            links,
            metrics,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(subscriptions.unsubscribeToken(ACCOUNT_ID, NotificationSubscriptionKind.MENTOR_DIGEST, NOW))
                .thenReturn(Optional.of("token"));
        when(contacts.activeVerifiedPrimaryEmail(ACCOUNT_ID)).thenReturn(Optional.of("dev@example.org"));
        when(memberships.membershipsForAccount(ACCOUNT_ID))
                .thenReturn(List.of(new AccountWorkspaceMembershipQuery.WorkspaceMembershipView(
                        7L, "delivery-team", "Delivery team", "MEMBER", 501L, List.of(501L))));
        when(links.url("token")).thenReturn("https://hephaestus.example/notifications/unsubscribe/token");
        when(links.confirmationUrl("token")).thenReturn("https://hephaestus.example/unsubscribe?token=token");
        when(gateway.configured()).thenReturn(true);
    }

    @Test
    void shouldSendOneNoteWithoutContentsAndWithOneClickUnsubscribeWhenTheDigestPointsToEmail() {
        when(gateway.send(any())).thenReturn(EmailDeliveryResult.sent("id"));

        listener.on(written(WeeklyDigestPointer.EMAIL, NOW));

        var sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(gateway).send(sent.capture());
        EmailMessage message = sent.getValue();
        assertThat(message.kind()).isEqualTo(EmailKind.MENTOR_DIGEST);
        assertThat(message.to()).isEqualTo("dev@example.org");
        assertThat(message.subject()).isEqualTo("Your weekly digest is ready");
        assertThat(message.unsubscribeUrl()).isEqualTo("https://hephaestus.example/notifications/unsubscribe/token");
        assertThat(message.text())
                .contains(
                        "Your weekly digest for 5–11 October in Delivery team is ready in Hephaestus.",
                        "https://hephaestus.example/w/delivery-team/mentor?entry=" + ENTRY_ID,
                        "Replies to this email are not read.")
                .doesNotContainPattern("\\d+ pieces? of feedback");
    }

    @Test
    void shouldSendNothingWhenTheAccountTurnedTheDigestOffOrTheNoteIsForSlack() {
        when(subscriptions.unsubscribeToken(ACCOUNT_ID, NotificationSubscriptionKind.MENTOR_DIGEST, NOW))
                .thenReturn(Optional.empty());

        listener.on(written(WeeklyDigestPointer.EMAIL, NOW));
        listener.on(written(WeeklyDigestPointer.SLACK, NOW));

        verify(metrics).record(EmailKind.MENTOR_DIGEST, EmailDeliveryResult.Outcome.UNSUBSCRIBED);
        verify(gateway, never()).send(any());
    }

    @Test
    void shouldSendNothingWhenTheNoteIsADayOld() {
        listener.on(written(WeeklyDigestPointer.EMAIL, NOW.minus(Duration.ofHours(24))));

        verify(metrics).record(EmailKind.MENTOR_DIGEST, EmailDeliveryResult.Outcome.EXPIRED);
        verify(gateway, never()).send(any());
    }

    @Test
    void shouldAskForRedeliveryWhenTheRelayIsUnavailable() {
        when(gateway.send(any())).thenReturn(EmailDeliveryResult.of(EmailDeliveryResult.Outcome.UNAVAILABLE));

        assertThatThrownBy(() -> listener.on(written(WeeklyDigestPointer.EMAIL, NOW)))
                .isInstanceOf(NotificationDeliveryUnavailableException.class);
    }

    @Test
    void shouldReachTheDeveloperOnlyWhenTheyHaveAVerifiedAddressAndTheRelayIsConfigured() {
        assertThat(listener.reaches(7L, ACCOUNT_ID, 501L)).isTrue();

        when(gateway.configured()).thenReturn(false);
        assertThat(listener.reaches(7L, ACCOUNT_ID, 501L))
                .as("relay not configured")
                .isFalse();

        when(gateway.configured()).thenReturn(true);
        when(contacts.activeVerifiedPrimaryEmail(ACCOUNT_ID)).thenReturn(Optional.empty());
        assertThat(listener.reaches(7L, ACCOUNT_ID, 501L))
                .as("no verified address")
                .isFalse();
    }

    private static WeeklyDigestWritten written(WeeklyDigestPointer pointer, Instant at) {
        return new WeeklyDigestWritten(ENTRY_ID, 7L, ACCOUNT_ID, "5–11 October", pointer, at);
    }
}
