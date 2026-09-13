package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.event.AccountDeletionScheduledEvent;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryMetrics;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import de.tum.cit.aet.hephaestus.notification.email.EmailGateway;
import de.tum.cit.aet.hephaestus.notification.email.EmailKind;
import de.tum.cit.aet.hephaestus.notification.email.EmailMessage;
import de.tum.cit.aet.hephaestus.notification.email.EmailRenderer;
import de.tum.cit.aet.hephaestus.notification.email.RenderedEmail;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountDeletionEmailListenerTest extends BaseUnitTest {

    private static final AccountDeletionScheduledEvent EVENT =
            new AccountDeletionScheduledEvent(42L, Instant.parse("2026-09-14T10:00:00Z"));

    private final AccountContactQuery contacts = mock(AccountContactQuery.class);
    private final EmailRenderer renderer = mock(EmailRenderer.class);
    private final EmailGateway gateway = mock(EmailGateway.class);
    private final EmailDeliveryMetrics metrics = mock(EmailDeliveryMetrics.class);
    private final AccountDeletionEmailListener listener = new AccountDeletionEmailListener(
            contacts, renderer, gateway, metrics, Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void shouldExpireAtThePurgeDeadlineWithoutResolvingOrSending() {
        listener.on(new AccountDeletionScheduledEvent(42L, Instant.parse("2026-09-12T10:00:00Z")));

        verify(metrics).record(EmailKind.ACCOUNT_DELETION_SCHEDULED, Outcome.EXPIRED);
        verify(contacts, never()).verifiedPrimaryEmail(42L);
        verify(gateway, never()).send(any());
    }

    @Test
    void shouldEmailTheVerifiedAddressWithThePurgeDate() {
        when(contacts.verifiedPrimaryEmail(42L)).thenReturn(Optional.of("dev@example.org"));
        when(renderer.render(any(), any())).thenReturn(new RenderedEmail("subject", "text", "html"));
        when(gateway.send(any())).thenReturn(EmailDeliveryResult.sent("<id@example>"));

        listener.on(EVENT);

        ArgumentCaptor<Map<String, Object>> model = ArgumentCaptor.captor();
        verify(renderer).render(org.mockito.ArgumentMatchers.eq(EmailKind.ACCOUNT_DELETION_SCHEDULED), model.capture());
        assertThat(model.getValue()).containsEntry("purgeAfter", "14 September 2026 at 10:00 UTC");
        ArgumentCaptor<EmailMessage> message = ArgumentCaptor.captor();
        verify(gateway).send(message.capture());
        assertThat(message.getValue().to()).isEqualTo("dev@example.org");
        assertThat(message.getValue().kind()).isEqualTo(EmailKind.ACCOUNT_DELETION_SCHEDULED);
    }

    @Test
    void shouldWithholdAndRecordWhenTheAccountHasNoVerifiedAddress() {
        when(contacts.verifiedPrimaryEmail(42L)).thenReturn(Optional.empty());

        listener.on(EVENT);

        verify(metrics).record(EmailKind.ACCOUNT_DELETION_SCHEDULED, Outcome.NO_RECIPIENT);
        verify(gateway, never()).send(any());
    }

    @Test
    void shouldThrowOnlyWhenTheTransportMayRecover() {
        when(contacts.verifiedPrimaryEmail(42L)).thenReturn(Optional.of("dev@example.org"));
        when(renderer.render(any(), any())).thenReturn(new RenderedEmail("subject", "text", "html"));

        when(gateway.send(any())).thenReturn(EmailDeliveryResult.of(Outcome.UNAVAILABLE));
        assertThatThrownBy(() -> listener.on(EVENT)).isInstanceOf(NotificationDeliveryUnavailableException.class);

        when(gateway.send(any())).thenReturn(EmailDeliveryResult.of(Outcome.SILENT_MODE));
        listener.on(EVENT);
        when(gateway.send(any())).thenReturn(EmailDeliveryResult.of(Outcome.REJECTED));
        listener.on(EVENT);
    }
}
