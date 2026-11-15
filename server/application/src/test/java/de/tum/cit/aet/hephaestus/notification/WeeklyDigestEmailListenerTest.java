package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.WeeklyDigestPointer;
import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.WeeklyDigestWritten;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import de.tum.cit.aet.hephaestus.notification.email.EmailKind;
import de.tum.cit.aet.hephaestus.notification.email.EmailMessage;
import de.tum.cit.aet.hephaestus.notification.email.EmailNotice.Address;
import de.tum.cit.aet.hephaestus.notification.email.EmailNotice.Prepared;
import de.tum.cit.aet.hephaestus.notification.email.EmailTestSupport;
import de.tum.cit.aet.hephaestus.notification.email.EmailUnsubscribeLinks;
import de.tum.cit.aet.hephaestus.notification.email.Emails;
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

class WeeklyDigestEmailListenerTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-10-12T07:05:00Z");
    private static final long ACCOUNT_ID = 42L;
    private static final UUID ENTRY_ID = UUID.fromString("0f8a6c1e-3b0d-4a43-9f43-3bd1d5a0e2c4");

    private final NotificationSubscriptionService subscriptions = mock(NotificationSubscriptionService.class);
    private final AccountContactQuery contacts = mock(AccountContactQuery.class);
    private final AccountMembershipQuery memberships = mock(AccountMembershipQuery.class);
    private final Emails emails = mock(Emails.class);
    private final EmailUnsubscribeLinks links = mock(EmailUnsubscribeLinks.class);
    private final WeeklyDigestEmailListener listener = new WeeklyDigestEmailListener(
            subscriptions,
            contacts,
            memberships,
            EmailTestSupport.renderer(),
            emails,
            links,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(subscriptions.unsubscribeToken(ACCOUNT_ID, NotificationSubscriptionKind.WEEKLY_DIGEST, NOW))
                .thenReturn(Optional.of("token"));
        when(contacts.activeVerifiedPrimaryEmail(ACCOUNT_ID)).thenReturn(Optional.of("dev@example.org"));
        when(memberships.membershipsForAccount(ACCOUNT_ID))
                .thenReturn(List.of(new AccountMembershipQuery.MembershipView(
                        7L, "delivery-team", "Delivery team", AccountMembershipQuery.Role.MEMBER)));
        when(links.url("token")).thenReturn("https://hephaestus.example/notifications/unsubscribe/token");
        when(links.confirmationUrl("token")).thenReturn("https://hephaestus.example/unsubscribe?token=token");
        when(emails.configured()).thenReturn(true);
    }

    @Test
    void shouldSendOneNoteWithoutContentsAndWithOneClickUnsubscribeWhenTheDigestPointsToEmail() {
        EmailMessage message = sent(listener.prepare(written(WeeklyDigestPointer.EMAIL, NOW)));
        assertThat(message.kind()).isEqualTo(EmailKind.WEEKLY_DIGEST);
        assertThat(message.to()).isEqualTo("dev@example.org");
        assertThat(message.subject()).isEqualTo("Your weekly digest is ready");
        assertThat(message.unsubscribeUrl()).isEqualTo("https://hephaestus.example/notifications/unsubscribe/token");
        assertThat(message.text())
                .contains(
                        "Your weekly digest for 5–11 October in Delivery team is ready in Hephaestus.",
                        "https://hephaestus.example/w/delivery-team/conversation?entry=" + ENTRY_ID,
                        "Replies to this email are not read.")
                .doesNotContainPattern("\\d+ pieces? of feedback");
    }

    @Test
    void shouldSendNothingWhenTheAccountTurnedTheDigestOffOrTheNoteIsForSlack() {
        when(subscriptions.unsubscribeToken(ACCOUNT_ID, NotificationSubscriptionKind.WEEKLY_DIGEST, NOW))
                .thenReturn(Optional.empty());

        assertThat(listener.prepare(written(WeeklyDigestPointer.EMAIL, NOW)))
                .isEqualTo(Prepared.withhold(Outcome.UNSUBSCRIBED));
        listener.on(written(WeeklyDigestPointer.SLACK, NOW));
        verify(emails, never()).enqueue(any(), any());
    }

    @Test
    void shouldSendNothingWhenTheNoteIsADayOld() {
        assertThat(listener.prepare(written(WeeklyDigestPointer.EMAIL, NOW.minus(Duration.ofHours(24)))))
                .isEqualTo(Prepared.withhold(Outcome.EXPIRED));
    }

    @Test
    void shouldAddressOneNotePerDigestInItsWorkspaceThatExpiresAfterADay() {
        assertThat(listener.address(written(WeeklyDigestPointer.EMAIL, NOW)))
                .isEqualTo(new Address(ENTRY_ID + ":" + ACCOUNT_ID, ACCOUNT_ID, 7L, NOW.plus(Duration.ofHours(24))));
    }

    @Test
    void shouldReachTheDeveloperOnlyWhenTheyHaveAVerifiedAddressAndTheRelayIsConfigured() {
        assertThat(listener.reaches(7L, ACCOUNT_ID)).isTrue();

        when(emails.configured()).thenReturn(false);
        assertThat(listener.reaches(7L, ACCOUNT_ID)).as("relay not configured").isFalse();

        when(emails.configured()).thenReturn(true);
        when(contacts.activeVerifiedPrimaryEmail(ACCOUNT_ID)).thenReturn(Optional.empty());
        assertThat(listener.reaches(7L, ACCOUNT_ID)).as("no verified address").isFalse();
    }

    private static WeeklyDigestWritten written(WeeklyDigestPointer pointer, Instant at) {
        return new WeeklyDigestWritten(ENTRY_ID, 7L, ACCOUNT_ID, "5–11 October", pointer, at);
    }

    private static EmailMessage sent(Prepared prepared) {
        assertThat(prepared).isInstanceOf(Prepared.Send.class);
        return ((Prepared.Send) prepared).message();
    }
}
