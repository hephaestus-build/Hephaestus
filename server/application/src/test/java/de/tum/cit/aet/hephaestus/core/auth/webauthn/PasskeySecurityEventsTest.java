package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventWriter;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;

class PasskeySecurityEventsTest extends BaseUnitTest {
    @ParameterizedTest
    @EnumSource(
            value = AuthEvent.EventType.class,
            names = {"PASSKEY_CHANGED", "PASSKEY_VERIFIED"})
    void shouldRefuseToCommitAPasskeyChangeWhenItsAuditCannotBeWritten(AuthEvent.EventType type) {
        var writer = mock(AuthEventWriter.class);
        when(writer.writeInCurrentTransaction(any())).thenReturn(false);
        var security = new PasskeySecurityEvents(
                new AuthEventLogger(writer), mock(ApplicationEventPublisher.class), Clock.systemUTC());

        assertThatThrownBy(() -> security.success(new PasskeySecurityEvents.PasskeySuccess(1L, type, "verified")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The passkey audit could not be recorded");
    }
}
