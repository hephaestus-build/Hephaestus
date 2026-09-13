package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.notification.email.*;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionKind;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackNotificationQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductFeedbackDigestListenerTest extends BaseUnitTest {
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    private final AccountContactQuery contacts = mock(AccountContactQuery.class);
    private final NotificationSubscriptionService subscriptions = mock(NotificationSubscriptionService.class);
    private final ProductFeedbackNotificationQuery feedback = mock(ProductFeedbackNotificationQuery.class);
    private final EmailRenderer renderer = mock(EmailRenderer.class);
    private final EmailGateway gateway = mock(EmailGateway.class);
    private final EmailDeliveryMetrics metrics = mock(EmailDeliveryMetrics.class);
    private final EmailUnsubscribeLinks links = mock(EmailUnsubscribeLinks.class);
    private final ProductFeedbackDigestListener listener = new ProductFeedbackDigestListener(
            contacts, subscriptions, feedback, renderer, gateway, metrics, links, Clock.fixed(NOW, ZoneOffset.UTC));
    private final ProductFeedbackDigestRequested event =
            new ProductFeedbackDigestRequested(42L, NOW.minusSeconds(86400), NOW);

    @Test
    void shouldSuppressAWindowFromBeforeTheCurrentSubscription() {
        listener.on(event);
        verify(metrics).record(EmailKind.PRODUCT_FEEDBACK_DIGEST, EmailDeliveryResult.Outcome.UNSUBSCRIBED);
        verifyNoInteractions(contacts, feedback, gateway);
    }

    @Test
    void shouldNeverSendAnEmptyDigest() {
        eligible();
        listener.on(event);
        verifyNoInteractions(renderer, gateway);
    }

    @Test
    void shouldRecheckAdministratorEligibilityBeforeCountingFeedback() {
        when(subscriptions.unsubscribeToken(42L, NotificationSubscriptionKind.PRODUCT_FEEDBACK))
                .thenReturn(Optional.of("token"));
        when(subscriptions.isDigestWindowCurrent(42L, event.from())).thenReturn(true);
        listener.on(event);
        verify(metrics).record(EmailKind.PRODUCT_FEEDBACK_DIGEST, EmailDeliveryResult.Outcome.NO_RECIPIENT);
        verifyNoInteractions(feedback, gateway);
    }

    @Test
    void shouldKeepRateLimitedDigestsAvailableForRetry() {
        eligible();
        when(feedback.countBetween(event.from(), event.until())).thenReturn(3L);
        when(links.confirmationUrl("token")).thenReturn("https://example.org/unsubscribe?token=token");
        when(links.url("token")).thenReturn("https://example.org/notifications/unsubscribe/token");
        when(renderer.render(any(), any())).thenReturn(new RenderedEmail("subject", "3 reports", "<p>3 reports</p>"));
        when(gateway.send(any())).thenReturn(EmailDeliveryResult.of(EmailDeliveryResult.Outcome.RATE_LIMITED));
        assertThatThrownBy(() -> listener.on(event)).isInstanceOf(NotificationDeliveryUnavailableException.class);
    }

    private void eligible() {
        when(subscriptions.unsubscribeToken(42L, NotificationSubscriptionKind.PRODUCT_FEEDBACK))
                .thenReturn(Optional.of("token"));
        when(subscriptions.isDigestWindowCurrent(42L, event.from())).thenReturn(true);
        when(contacts.activeVerifiedAdministratorEmail(42L)).thenReturn(Optional.of("admin@example.org"));
    }
}
