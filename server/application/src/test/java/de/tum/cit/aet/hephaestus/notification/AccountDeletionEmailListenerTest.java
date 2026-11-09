package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.event.AccountDeletionScheduledEvent;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import de.tum.cit.aet.hephaestus.notification.email.EmailKind;
import de.tum.cit.aet.hephaestus.notification.email.EmailMessage;
import de.tum.cit.aet.hephaestus.notification.email.EmailNotice.Address;
import de.tum.cit.aet.hephaestus.notification.email.EmailNotice.Prepared;
import de.tum.cit.aet.hephaestus.notification.email.EmailRenderer;
import de.tum.cit.aet.hephaestus.notification.email.Emails;
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
    private final Emails emails = mock(Emails.class);
    private final AccountDeletionEmailListener listener = new AccountDeletionEmailListener(
            contacts, renderer, emails, Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void shouldExpireAtThePurgeDeadlineWithoutResolvingTheAddress() {
        assertThat(listener.prepare(new AccountDeletionScheduledEvent(42L, Instant.parse("2026-09-12T10:00:00Z"))))
                .isEqualTo(Prepared.withhold(Outcome.EXPIRED));
        verify(contacts, never()).verifiedPrimaryEmail(42L);
    }

    @Test
    void shouldEmailTheVerifiedAddressWithThePurgeDate() {
        when(contacts.verifiedPrimaryEmail(42L)).thenReturn(Optional.of("dev@example.org"));
        when(renderer.render(any(), any())).thenReturn(new RenderedEmail("subject", "text", "html"));

        EmailMessage message = ((Prepared.Send) listener.prepare(EVENT)).message();

        ArgumentCaptor<Map<String, Object>> model = ArgumentCaptor.captor();
        verify(renderer).render(eq(EmailKind.ACCOUNT_DELETION_SCHEDULED), model.capture());
        assertThat(model.getValue()).containsEntry("purgeAfter", "14 September 2026 at 10:00 UTC");
        assertThat(message.to()).isEqualTo("dev@example.org");
        assertThat(message.kind()).isEqualTo(EmailKind.ACCOUNT_DELETION_SCHEDULED);
    }

    @Test
    void shouldWithholdWhenTheAccountHasNoVerifiedAddress() {
        when(contacts.verifiedPrimaryEmail(42L)).thenReturn(Optional.empty());

        assertThat(listener.prepare(EVENT)).isEqualTo(Prepared.withhold(Outcome.NO_RECIPIENT));
    }

    @Test
    void shouldAddressOneEmailPerScheduledPurgeThatExpiresAtThePurge() {
        assertThat(listener.address(EVENT))
                .isEqualTo(new Address("42:" + EVENT.purgeAfter().toEpochMilli(), 42L, null, EVENT.purgeAfter()));
    }

    @Test
    void shouldHandTheEventToTheOutbox() {
        listener.on(EVENT);

        verify(emails).enqueue(listener, EVENT);
    }
}
