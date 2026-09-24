package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.event.AccountSecurityChangedEvent;
import de.tum.cit.aet.hephaestus.notification.email.*;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AccountSecurityEmailListenerTest extends BaseUnitTest {
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    private final AccountContactQuery contacts = mock(AccountContactQuery.class);
    private final EmailRenderer renderer = mock(EmailRenderer.class);
    private final EmailGateway gateway = mock(EmailGateway.class);
    private final EmailDeliveryMetrics metrics = mock(EmailDeliveryMetrics.class);
    private final AccountSecurityEmailListener listener =
            new AccountSecurityEmailListener(contacts, renderer, gateway, metrics, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void shouldExpireAtTwentyFourHoursWithoutReadingContactOrSending() {
        listener.on(new AccountSecurityChangedEvent(
                42L, AccountSecurityChangedEvent.Kind.IDENTITY_LINKED, NOW.minusSeconds(86400)));
        verify(metrics).record(EmailKind.ACCOUNT_SECURITY_CHANGED, EmailDeliveryResult.Outcome.EXPIRED);
        verifyNoInteractions(contacts, renderer, gateway);
    }

    @Test
    void shouldLeaveTransientFailuresAvailableForRegistryRetry() {
        when(contacts.activeVerifiedPrimaryEmail(42L)).thenReturn(Optional.of("verified@example.org"));
        when(renderer.render(any(), any())).thenReturn(new RenderedEmail("subject", "text", "html"));
        when(gateway.send(any())).thenReturn(EmailDeliveryResult.of(EmailDeliveryResult.Outcome.UNAVAILABLE));
        assertThatThrownBy(() -> listener.on(
                        new AccountSecurityChangedEvent(42L, AccountSecurityChangedEvent.Kind.IDENTITY_UNLINKED, NOW)))
                .isInstanceOf(NotificationDeliveryUnavailableException.class);
    }
}
