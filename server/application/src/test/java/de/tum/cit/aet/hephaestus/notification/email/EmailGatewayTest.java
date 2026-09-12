package de.tum.cit.aet.hephaestus.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import de.tum.cit.aet.hephaestus.notification.metrics.NotificationMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.net.ConnectException;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

class EmailGatewayTest extends BaseUnitTest {

    private static final EmailMessage MESSAGE =
            new EmailMessage(EmailKind.TEST_MESSAGE, "dev@example.org", "Test email", "plain body", "<p>html body</p>");

    private final CapturingJavaMailSender sender = new CapturingJavaMailSender();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private boolean silentMode;

    private EmailGateway gateway(Optional<JavaMailSender> transport, EmailProperties properties) {
        return new EmailGateway(
                transport, properties, new OutboundEgressGuard(() -> silentMode), new EmailDeliveryMetrics(registry));
    }

    private EmailGateway configuredGateway() {
        return gateway(Optional.of(sender), new EmailProperties("noreply@hephaestus.example", "Hephaestus", null));
    }

    @Test
    void shouldSendMultipartMessageWithSenderIdentityAndAutomationHeaders() throws Exception {
        EmailGateway gateway = gateway(
                Optional.of(sender),
                new EmailProperties("noreply@hephaestus.example", "Hephaestus", "team@hephaestus.example"));

        EmailDeliveryResult result = gateway.send(MESSAGE);

        assertThat(result.outcome()).isEqualTo(Outcome.SENT);
        assertThat(result.messageId()).matches("<[0-9a-f-]{36}@hephaestus\\.example>");
        assertThat(sender.sent()).hasSize(1);
        MimeMessage sent = sender.sent().getFirst();
        assertThat(sent.getHeader("Message-ID")).containsExactly(result.messageId());
        assertThat(sent.getFrom()).containsExactly(new InternetAddress("noreply@hephaestus.example", "Hephaestus"));
        assertThat(sent.getReplyTo()).containsExactly(new InternetAddress("team@hephaestus.example"));
        assertThat(sent.getRecipients(Message.RecipientType.TO))
                .containsExactly(new InternetAddress("dev@example.org"));
        assertThat(sent.getSubject()).isEqualTo("Test email");
        assertThat(sent.getHeader("Auto-Submitted")).containsExactly("auto-generated");
        assertThat(sent.getHeader("X-Auto-Response-Suppress")).containsExactly("All");
        Multipart mixed = (Multipart) sent.getContent();
        assertThat(mixed.getCount()).isEqualTo(1);
        assertThat(mixed.getBodyPart(0).isMimeType("multipart/alternative")).isTrue();
        Multipart alternatives = (Multipart) mixed.getBodyPart(0).getContent();
        assertThat(alternatives.getCount()).isEqualTo(2);
        assertThat(alternatives.getBodyPart(0).isMimeType("text/plain")).isTrue();
        assertThat(alternatives.getBodyPart(0).getContent()).isEqualTo("plain body");
        assertThat(alternatives.getBodyPart(1).isMimeType("text/html")).isTrue();
        assertThat(alternatives.getBodyPart(1).getContent()).isEqualTo("<p>html body</p>");
        assertThat(registry.get(NotificationMetrics.EMAIL_DELIVERY)
                        .tag("kind", "test_message")
                        .tag("outcome", "sent")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldReportNotConfiguredWhenThereIsNoRelay() {
        EmailGateway gateway =
                gateway(Optional.empty(), new EmailProperties("noreply@hephaestus.example", "Hephaestus", null));

        assertThat(gateway.configured()).isFalse();
        assertThat(gateway.send(MESSAGE).outcome()).isEqualTo(Outcome.NOT_CONFIGURED);
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void shouldReportNotConfiguredWhenTheSenderAddressIsMissing() {
        EmailGateway gateway = gateway(Optional.of(sender), new EmailProperties("", "Hephaestus", null));

        assertThat(gateway.configured()).isFalse();
        assertThat(gateway.send(MESSAGE).outcome()).isEqualTo(Outcome.NOT_CONFIGURED);
        assertThat(sender.sent()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "not an address",
                "a@example.org, b@example.org",
                "a@example.org\r\nBcc: c@example.org",
                "team:a@example.org,b@example.org;",
                "undisclosed:;"
            })
    void shouldRefuseAnythingButOneValidMailbox(String to) {
        EmailGateway gateway = configuredGateway();

        EmailDeliveryResult result = gateway.send(new EmailMessage(EmailKind.TEST_MESSAGE, to, "s", "t", "h"));

        assertThat(result.outcome()).isEqualTo(Outcome.INVALID_ADDRESS);
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void shouldWithholdUnderSilentModeWithoutTouchingTheRelay() {
        silentMode = true;
        EmailGateway gateway = configuredGateway();

        assertThat(gateway.send(MESSAGE).outcome()).isEqualTo(Outcome.SILENT_MODE);
        assertThat(sender.sent()).isEmpty();
        assertThat(registry.get(NotificationMetrics.EMAIL_DELIVERY)
                        .tag("outcome", "silent_mode")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldClassifyConnectionFailureAsUnavailable() {
        sender.failWith(new MailSendException("connect", new ConnectException("refused")));

        assertThat(configuredGateway().send(MESSAGE).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    void shouldClassifyBadCredentialsAsUnavailable() {
        sender.failWith(new MailAuthenticationException("535"));

        assertThat(configuredGateway().send(MESSAGE).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    void shouldClassifyRefusedMailboxAsRejected() throws MessagingException {
        Address[] invalid = {new InternetAddress("dev@example.org")};
        SendFailedException refused = new SendFailedException("550 no such user", null, null, null, invalid);
        sender.failWith(new MailSendException(Map.of(new MimeMessage((jakarta.mail.Session) null), refused)));

        EmailDeliveryResult result = configuredGateway().send(MESSAGE);

        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.outcome().retryable()).isFalse();
    }

    @Test
    void shouldNeverLogRelayRepliesOrExceptionsEvenAtDebugLevel() {
        Logger logger = (Logger) LoggerFactory.getLogger(EmailGateway.class);
        var originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            sender.failWith(new MailSendException("relay rejected dev@example.org and secret body"));
            configuredGateway().send(MESSAGE);

            assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("dev@example.org", "secret body");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }
}
