package de.tum.cit.aet.hephaestus.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
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
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class EmailAdapterTest extends BaseUnitTest {

    private static final EmailMessage MESSAGE = new EmailMessage(
            EmailKind.TEST_MESSAGE, "dev@example.org", "Test email", "plain body", "<p>html body</p>", null);

    private final CapturingJavaMailSender sender = new CapturingJavaMailSender();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private boolean silentMode;
    private final EmailRateLimiter rateLimiter = mock(EmailRateLimiter.class);

    @BeforeEach
    void allowCapacity() {
        when(rateLimiter.acquire(anyBoolean())).thenReturn(Optional.empty());
    }

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private boolean fenceAccepts = true;
    private int fenced;

    private EmailAdapter adapter(Optional<JavaMailSender> transport, EmailProperties properties) {
        return new EmailAdapter(
                transport,
                properties,
                new OutboundEgressGuard(() -> silentMode),
                new EmailDeliveryMetrics(registry),
                rateLimiter,
                MAPPER,
                List.of(new DirectEmailNotice()));
    }

    private EmailAdapter configuredAdapter() {
        return adapter(Optional.of(sender), new EmailProperties("noreply@hephaestus.example", "Hephaestus", null));
    }

    /** One outbox attempt of the message under the given idempotency key. */
    private ActionResult execute(EmailAdapter adapter, EmailMessage message, String key) {
        return adapter.execute(new ActionAttempt(
                UUID.randomUUID(), null, ActionKind.EMAIL, key, payload(message), 1, 1, false, null, () -> {
                    fenced++;
                    return fenceAccepts;
                }));
    }

    private static JsonNode payload(EmailMessage message) {
        return MAPPER.createObjectNode()
                .put(EmailAdapter.PAYLOAD_KIND, EmailKind.TEST_MESSAGE.name())
                .set(EmailAdapter.PAYLOAD_EVENT, MAPPER.valueToTree(new DirectEmailNotice.Direct(message)));
    }

    private EmailDeliveryResult send(EmailAdapter adapter, EmailMessage message) {
        ActionResult result = execute(adapter, message, "email:test:" + UUID.randomUUID());
        return new EmailDeliveryResult(
                EmailAdapter.outcome(result), result instanceof ActionResult.Done done ? done.readbackRef() : null);
    }

    @Test
    void shouldSendMultipartMessageWithSenderIdentityAndAutomationHeaders() throws Exception {
        EmailAdapter adapter = adapter(
                Optional.of(sender),
                new EmailProperties("noreply@hephaestus.example", "Hephaestus", "team@hephaestus.example"));

        EmailDeliveryResult result = send(adapter, MESSAGE);

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
        assertThat(sent.getHeader("List-Unsubscribe")).isNull();
        assertThat(sent.getHeader("List-Unsubscribe-Post")).isNull();
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
    void shouldAdvertiseOneClickOnlyForHttpsUnsubscribeLinks() throws Exception {
        for (String scheme : List.of("https", "http")) {
            String url = scheme + "://example.org/notifications/unsubscribe/opaque-token";
            send(configuredAdapter(), new EmailMessage(EmailKind.TEST_MESSAGE, MESSAGE.to(), "s", "t", "h", url));
            MimeMessage sent = sender.sent().getLast();
            assertThat(sent.getHeader("List-Unsubscribe")).containsExactly("<" + url + ">");
            if ("https".equals(scheme)) {
                assertThat(sent.getHeader("List-Unsubscribe-Post")).containsExactly("List-Unsubscribe=One-Click");
            } else {
                assertThat(sent.getHeader("List-Unsubscribe-Post")).isNull();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://example.org/\r\nBcc:other@example.org",
                "mailto:other@example.org",
                "https://user:password@example.org/unsubscribe",
                "/unsubscribe",
                "https://example.org/#token"
            })
    void shouldRejectUnsafeUnsubscribeLinks(String url) {
        assertThatThrownBy(() -> new EmailMessage(EmailKind.TEST_MESSAGE, MESSAGE.to(), "s", "t", "h", url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseOptionalMailWithoutAnUnsubscribeCapability() {
        assertThatThrownBy(() -> EmailMessage.of(
                        EmailKind.PRODUCT_FEEDBACK, "dev@example.org", new RenderedEmail("subject", "text", "html")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReportNotConfiguredWhenThereIsNoRelay() {
        EmailAdapter adapter =
                adapter(Optional.empty(), new EmailProperties("noreply@hephaestus.example", "Hephaestus", null));

        assertThat(send(adapter, MESSAGE).outcome()).isEqualTo(Outcome.NOT_CONFIGURED);
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void shouldReportNotConfiguredWhenTheSenderAddressIsMissing() {
        EmailAdapter adapter = adapter(Optional.of(sender), new EmailProperties("", "Hephaestus", null));

        assertThat(send(adapter, MESSAGE).outcome()).isEqualTo(Outcome.NOT_CONFIGURED);
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
        EmailAdapter adapter = configuredAdapter();

        EmailDeliveryResult result = send(adapter, new EmailMessage(EmailKind.TEST_MESSAGE, to, "s", "t", "h", null));

        assertThat(result.outcome()).isEqualTo(Outcome.INVALID_ADDRESS);
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void shouldWithholdUnderSilentModeWithoutTouchingTheRelay() {
        silentMode = true;
        EmailAdapter adapter = configuredAdapter();

        assertThat(send(adapter, MESSAGE).outcome()).isEqualTo(Outcome.SILENT_MODE);
        assertThat(sender.sent()).isEmpty();
        assertThat(registry.get(NotificationMetrics.EMAIL_DELIVERY)
                        .tag("outcome", "silent_mode")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldClassifyConnectionFailureAsUnavailable() {
        // JavaMailSenderImpl names the connection failure as the cause and as every message's failure.
        var refused = new MessagingException("connect", new ConnectException("refused"));
        sender.failWith(new MailSendException(
                "Mail server connection failed", refused, Map.of(new MimeMessage((Session) null), refused)));

        assertThat(send(configuredAdapter(), MESSAGE).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    void shouldClassifyBadCredentialsAsUnavailable() {
        sender.failWith(new MailAuthenticationException("535"));

        assertThat(send(configuredAdapter(), MESSAGE).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    void shouldClassifyRefusedMailboxAsRejected() throws MessagingException {
        Address[] invalid = {new InternetAddress("dev@example.org")};
        SendFailedException refused = new SendFailedException("550 no such user", null, null, null, invalid);
        sender.failWith(new MailSendException(Map.of(new MimeMessage((Session) null), refused)));

        EmailDeliveryResult result = send(configuredAdapter(), MESSAGE);

        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
    }

    @ParameterizedTest
    @CsvSource({"451, UNAVAILABLE", "554, REJECTED"})
    void shouldClassifyDataFailureWithoutInvalidAddresses(int code, Outcome expected) {
        var failure = new SMTPSendFailedException(".", code, "relay response", null, null, null, null);
        sender.failWith(new MailSendException(Map.of(new MimeMessage((Session) null), failure)));

        assertThat(send(configuredAdapter(), MESSAGE).outcome()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"450, UNAVAILABLE", "550, REJECTED"})
    void shouldClassifyNestedSenderFailure(int code, Outcome expected) throws MessagingException {
        var failure = new SMTPSenderFailedException(
                new InternetAddress("noreply@hephaestus.example"), "MAIL FROM", code, "relay response");
        sender.failWith(new MailSendException(
                Map.of(new MimeMessage((Session) null), new MessagingException("send failed", failure))));

        assertThat(send(configuredAdapter(), MESSAGE).outcome()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"450, UNAVAILABLE", "550, REJECTED"})
    void shouldClassifyNestedRecipientFailure(int code, Outcome expected) throws MessagingException {
        var failure = new SMTPAddressFailedException(
                new InternetAddress("dev@example.org"), "RCPT TO", code, "relay response");
        sender.failWith(new MailSendException(
                Map.of(new MimeMessage((Session) null), new MessagingException("recipient failed", failure))));

        assertThat(send(configuredAdapter(), MESSAGE).outcome()).isEqualTo(expected);
    }

    @Test
    void shouldGiveEachActionOneStableMessageId() throws Exception {
        execute(configuredAdapter(), MESSAGE, "email:test:one");
        execute(configuredAdapter(), MESSAGE, "email:test:one");
        execute(configuredAdapter(), MESSAGE, "email:test:two");

        assertThat(sender.sent()).hasSize(3);
        String first = sender.sent().get(0).getHeader("Message-ID")[0];
        assertThat(sender.sent().get(1).getHeader("Message-ID")).containsExactly(first);
        assertThat(sender.sent().get(2).getHeader("Message-ID")[0]).isNotEqualTo(first);
    }

    @Test
    void shouldNotTouchTheRelayWhenTheLeaseRefusesTheWrite() {
        fenceAccepts = false;

        ActionResult result = execute(configuredAdapter(), MESSAGE, "email:test:fenced");

        assertThat(result).isInstanceOf(ActionResult.Abandoned.class);
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void shouldFenceOnlyAfterEverythingThatCannotReachTheRelay() {
        silentMode = true;
        execute(configuredAdapter(), MESSAGE, "email:test:silent");
        silentMode = false;
        execute(
                configuredAdapter(),
                new EmailMessage(EmailKind.TEST_MESSAGE, "not an address", "s", "t", "h", null),
                "email:test:invalid");
        when(rateLimiter.acquire(anyBoolean())).thenReturn(Optional.of(Duration.ofMinutes(5)));
        ActionResult limited = execute(configuredAdapter(), MESSAGE, "email:test:limited");

        assertThat(limited)
                .as("waits until the budget refills")
                .isEqualTo(new ActionResult.Defer(Duration.ofMinutes(5)));
        assertThat(fenced).isZero();
    }

    @Test
    void shouldNeverSendAgainWhenTheRelayLeftTheOutcomeUnclear() {
        // A socket timeout after DATA: the relay may hold the message.
        sender.failWith(new MailSendException(Map.of(
                new MimeMessage((Session) null), new MessagingException("data", new IOException("read timed out")))));

        ActionResult result = execute(configuredAdapter(), MESSAGE, "email:test:unclear");

        assertThat(result).isEqualTo(new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED));
        assertThat(EmailAdapter.outcome(result)).isEqualTo(Outcome.UNCONFIRMED);
    }

    @Test
    void shouldRetryOnlyWhatTheRelayProvablyDidNotAccept() {
        var temporary = new SMTPSendFailedException(".", 451, "try later", null, null, null, null);
        sender.failWith(new MailSendException(Map.of(new MimeMessage((Session) null), temporary)));

        assertThat(execute(configuredAdapter(), MESSAGE, "email:test:451")).isInstanceOf(ActionResult.Retry.class);
    }

    @Test
    void shouldNeverLogRelayRepliesOrExceptionsEvenAtDebugLevel() {
        Logger logger = (Logger) LoggerFactory.getLogger(EmailAdapter.class);
        var originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            sender.failWith(new MailSendException("relay rejected dev@example.org and secret body"));
            send(configuredAdapter(), MESSAGE);

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
