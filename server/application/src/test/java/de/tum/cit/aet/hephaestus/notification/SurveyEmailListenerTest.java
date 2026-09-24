package de.tum.cit.aet.hephaestus.notification;

import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.config.ApplicationProperties;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.notification.email.*;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionKind;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import de.tum.cit.aet.hephaestus.productfeedback.notification.SurveyEmailInvitations;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SurveyEmailListenerTest extends BaseUnitTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldCompleteWithoutEmailConfigurationOrPublicHttpsWhenTransportIsOff(boolean reminder) {
        Instant now = Instant.parse("2026-09-14T10:00:00Z");
        UUID surveyId = UUID.randomUUID();
        var contacts = mock(AccountContactQuery.class);
        var subscriptions = mock(NotificationSubscriptionService.class);
        var invitations = mock(SurveyEmailInvitations.class);
        var metrics = mock(EmailDeliveryMetrics.class);
        var egress = mock(OutboundEgressGuard.class);
        var limits = mock(EmailRateLimiter.class);
        var gateway = new EmailGateway(
                Optional.empty(), new EmailProperties(null, "Hephaestus", null), egress, metrics, limits);
        var links = new EmailUnsubscribeLinks(new ApplicationProperties(
                "http://internal.example", new ApplicationProperties.Webapp("http://internal.example")));
        var listener = new SurveyEmailListener(
                contacts,
                subscriptions,
                invitations,
                EmailTestSupport.renderer(),
                gateway,
                metrics,
                links,
                Clock.fixed(now, ZoneOffset.UTC));
        when(invitations.eligibleInvitation(surveyId, 42L, reminder, 0L))
                .thenReturn(Optional.of(new SurveyEmailInvitations.Invitation(false, "team")));
        when(subscriptions.unsubscribeToken(42L, NotificationSubscriptionKind.PRODUCT_SURVEYS, now))
                .thenReturn(Optional.of(UUID.randomUUID().toString()));
        when(contacts.activeVerifiedPrimaryEmail(42L)).thenReturn(Optional.of("member@example.org"));

        listener.on(new SurveyEmailRequested(surveyId, 42L, now, now.plusSeconds(3600), reminder, 0L));

        verify(metrics).record(EmailKind.SURVEY_INVITATION, EmailDeliveryResult.Outcome.NOT_CONFIGURED);
        verifyNoMoreInteractions(metrics);
        verify(invitations, never()).markAccepted(any(), anyLong(), any(), anyBoolean(), anyLong());
        verifyNoInteractions(egress, limits);
    }
}
