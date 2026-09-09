package de.tum.cit.aet.hephaestus.integration.core.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.core.settings.spi.SilentModeQuery;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.mail.autoconfigure.MailProperties;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@Tag("unit")
class SmtpEmailGatewayTest {
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final SilentModeQuery silentMode = mock(SilentModeQuery.class);

    @Test
    void shouldSendThroughSpringMailWhenConfiguredAndAllowed() {
        var result = gateway("smtp.example.com").send("developer@example.com", "Access update", "Read the request.");
        assertThat(result).isEqualTo(SmtpEmailGateway.Result.SENT);
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().getFrom()).isEqualTo("hephaestus@example.com");
        assertThat(message.getValue().getTo()).containsExactly("developer@example.com");
        assertThat(message.getValue().getText()).isEqualTo("Read the request.");
    }

    @Test
    void shouldKeepEmailUnsentWhenSilentModeIsEngaged() {
        when(silentMode.isSilentModeEngaged()).thenReturn(true);
        assertThat(gateway("smtp.example.com").send("developer@example.com", "Access update", "Body"))
                .isEqualTo(SmtpEmailGateway.Result.SUPPRESSED);
        verifyNoInteractions(sender);
    }

    @Test
    void shouldFailClosedWhenSilentModeCannotBeRead() {
        when(silentMode.isSilentModeEngaged()).thenThrow(new IllegalStateException("settings unavailable"));
        assertThat(gateway("smtp.example.com").send("developer@example.com", "Access update", "Body"))
                .isEqualTo(SmtpEmailGateway.Result.SUPPRESSED);
        verifyNoInteractions(sender);
    }

    @Test
    void shouldNotFallBackToLocalSmtpWhenHostIsBlank() {
        assertThat(gateway("").send("developer@example.com", "Access update", "Body"))
                .isEqualTo(SmtpEmailGateway.Result.NOT_CONFIGURED);
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "first@example.com,second@example.com",
                "Developer <first@example.com>",
                "first@example.com\r\nBcc: second@example.com",
                "not-an-email"
            })
    void shouldRejectMoreThanOnePlainContactAddressWhenSending(String recipient) {
        assertThat(gateway("smtp.example.com").send(recipient, "Access update", "Body"))
                .isEqualTo(SmtpEmailGateway.Result.INVALID_ADDRESS);
        verifyNoInteractions(sender);
    }

    @Test
    void shouldExposeOnlyASafeFailureResultWhenSmtpFails() {
        doThrow(new MailSendException("provider response contains private delivery details"))
                .when(sender)
                .send(any(SimpleMailMessage.class));
        assertThat(gateway("smtp.example.com").send("developer@example.com", "Access update", "Body"))
                .isEqualTo(SmtpEmailGateway.Result.FAILED);
    }

    private SmtpEmailGateway gateway(String host) {
        var transport = new MailProperties();
        transport.setHost(host);
        return new SmtpEmailGateway(
                Optional.of(sender),
                new SmtpEmailProperties("hephaestus@example.com"),
                Optional.of(transport),
                new OutboundEgressGuard(silentMode));
    }
}
