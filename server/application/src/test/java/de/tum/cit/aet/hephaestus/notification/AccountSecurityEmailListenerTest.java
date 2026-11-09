package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.event.AccountSecurityChangedEvent;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import de.tum.cit.aet.hephaestus.notification.email.EmailNotice.Address;
import de.tum.cit.aet.hephaestus.notification.email.EmailNotice.Prepared;
import de.tum.cit.aet.hephaestus.notification.email.EmailRenderer;
import de.tum.cit.aet.hephaestus.notification.email.Emails;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AccountSecurityEmailListenerTest extends BaseUnitTest {
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    private final AccountContactQuery contacts = mock(AccountContactQuery.class);
    private final EmailRenderer renderer = mock(EmailRenderer.class);
    private final Emails emails = mock(Emails.class);
    private final AccountSecurityEmailListener listener =
            new AccountSecurityEmailListener(contacts, renderer, emails, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void shouldExpireAtTwentyFourHoursWithoutReadingContact() {
        assertThat(listener.prepare(new AccountSecurityChangedEvent(
                        42L, AccountSecurityChangedEvent.Kind.IDENTITY_LINKED, NOW.minusSeconds(86400))))
                .isEqualTo(Prepared.withhold(Outcome.EXPIRED));
        verifyNoInteractions(contacts, renderer);
    }

    @Test
    void shouldWithholdFromAnAccountWithoutAnActiveVerifiedAddress() {
        when(contacts.activeVerifiedPrimaryEmail(42L)).thenReturn(Optional.empty());

        assertThat(listener.prepare(
                        new AccountSecurityChangedEvent(42L, AccountSecurityChangedEvent.Kind.IDENTITY_UNLINKED, NOW)))
                .isEqualTo(Prepared.withhold(Outcome.NO_RECIPIENT));
    }

    @Test
    void shouldAddressOneEmailPerChangeThatExpiresAfterADay() {
        var event = new AccountSecurityChangedEvent(42L, AccountSecurityChangedEvent.Kind.IDENTITY_UNLINKED, NOW);

        assertThat(listener.address(event))
                .isEqualTo(new Address(
                        "42:IDENTITY_UNLINKED:" + NOW.toEpochMilli(), 42L, null, NOW.plus(Duration.ofHours(24))));
    }
}
