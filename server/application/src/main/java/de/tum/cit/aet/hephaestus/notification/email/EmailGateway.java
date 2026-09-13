package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** The SMTP boundary. Delivery policy lives in ADR 0044; outcomes are defined by {@link Outcome}. */
@Component
@OutboundEgressGateway
@ConditionalOnServerRole
public class EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(EmailGateway.class);

    static final String EGRESS_OPERATION = "email.send";

    private final Optional<JavaMailSender> mailSender;
    private final EmailProperties properties;
    private final OutboundEgressGuard egressGuard;
    private final EmailDeliveryMetrics metrics;
    private final EmailRateLimiter rateLimiter;

    public EmailGateway(
            Optional<JavaMailSender> mailSender,
            EmailProperties properties,
            OutboundEgressGuard egressGuard,
            EmailDeliveryMetrics metrics,
            EmailRateLimiter rateLimiter) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.egressGuard = egressGuard;
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
    }

    /** Whether this instance can send email at all: a relay host and a sender address are both set. */
    public boolean configured() {
        return mailSender.isPresent() && properties.hasFrom();
    }

    public EmailDeliveryResult send(EmailMessage message) {
        EmailDeliveryResult result = deliver(message);
        metrics.record(message.kind(), result.outcome());
        return result;
    }

    private EmailDeliveryResult deliver(EmailMessage message) {
        JavaMailSender sender = mailSender.orElse(null);
        String from = properties.from();
        if (sender == null || from == null || !properties.hasFrom()) {
            log.debug("email: not configured, withheld kind={}", message.kind().tag());
            return EmailDeliveryResult.of(Outcome.NOT_CONFIGURED);
        }
        InternetAddress to = parseSingleAddress(message.to());
        if (to == null) {
            log.warn(
                    "email: recipient address invalid, withheld kind={}",
                    message.kind().tag());
            return EmailDeliveryResult.of(Outcome.INVALID_ADDRESS);
        }
        try {
            egressGuard.requireDeliveryAllowed(EGRESS_OPERATION);
        } catch (OutboundEgressSuppressedException e) {
            log.info(
                    "email: Silent Mode engaged, withheld kind={}",
                    message.kind().tag());
            return EmailDeliveryResult.of(Outcome.SILENT_MODE);
        }

        if (!rateLimiter.acquire(message.kind().optional())) {
            return EmailDeliveryResult.of(Outcome.RATE_LIMITED);
        }

        String messageId = "<" + UUID.randomUUID() + "@" + domainOf(from) + ">";
        MimeMessage mimeMessage = sender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(
                    mimeMessage, MimeMessageHelper.MULTIPART_MODE_MIXED, StandardCharsets.UTF_8.name());
            helper.setFrom(from, properties.fromName());
            helper.setTo(to);
            String replyTo = properties.replyTo();
            if (replyTo != null && properties.hasReplyTo()) {
                helper.setReplyTo(replyTo);
            }
            helper.setSubject(message.subject());
            helper.setText(message.text(), message.html());
            // JavaMailSenderImpl keeps an explicitly set Message-ID across saveChanges().
            mimeMessage.setHeader("Message-ID", messageId);
            mimeMessage.setHeader("Auto-Submitted", "auto-generated");
            mimeMessage.setHeader("X-Auto-Response-Suppress", "All");
            String unsubscribeUrl = message.unsubscribeUrl();
            if (unsubscribeUrl != null) {
                mimeMessage.setHeader("List-Unsubscribe", "<" + unsubscribeUrl + ">");
                // RFC 8058 requires HTTPS. Local Mailpit links remain usable without advertising one-click.
                if (unsubscribeUrl.startsWith("https://")) {
                    mimeMessage.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
                }
            }
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error(
                    "email: could not build message kind={} messageId={} cause={}",
                    message.kind().tag(),
                    messageId,
                    e.getClass().getName());
            return EmailDeliveryResult.of(Outcome.REJECTED);
        }
        try {
            sender.send(mimeMessage);
        } catch (MailException e) {
            return failed(message.kind(), messageId, e);
        }
        log.info("email: sent kind={} messageId={}", message.kind().tag(), messageId);
        return EmailDeliveryResult.sent(messageId);
    }

    private static EmailDeliveryResult failed(EmailKind kind, String messageId, MailException e) {
        Outcome outcome = classify(e);
        Throwable root = rootCause(e);
        // The exception message can quote the recipient and the relay's reply; the class names are enough
        // to tell "connection refused" from "authentication failed" from "address rejected".
        log.warn(
                "email: delivery {} kind={} messageId={} cause={} rootCause={}",
                outcome.name().toLowerCase(java.util.Locale.ROOT),
                kind.tag(),
                messageId,
                e.getClass().getSimpleName(),
                root.getClass().getName());
        return EmailDeliveryResult.of(outcome);
    }

    /** SMTP 5xx responses are final; connection, TLS, credentials and 4xx failures may recover. */
    private static Outcome classify(MailException e) {
        if (e instanceof MailAuthenticationException) {
            return Outcome.UNAVAILABLE;
        }
        if (e instanceof MailSendException sendException) {
            for (Exception failure : sendException.getMessageExceptions()) {
                if (isPermanentFailure(failure)) {
                    return Outcome.REJECTED;
                }
            }
        }
        return isPermanentFailure(e) ? Outcome.REJECTED : Outcome.UNAVAILABLE;
    }

    private static boolean isPermanentFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            int code =
                    switch (current) {
                        case SMTPSendFailedException smtp -> smtp.getReturnCode();
                        case SMTPSenderFailedException smtp -> smtp.getReturnCode();
                        case SMTPAddressFailedException smtp -> smtp.getReturnCode();
                        default -> 0;
                    };
            if (code >= 400 && code < 600) {
                return code >= 500;
            }
            if (current instanceof SendFailedException sendFailed
                    && sendFailed.getInvalidAddresses() != null
                    && sendFailed.getInvalidAddresses().length > 0) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /** Strict RFC 822 parse of exactly one mailbox; CR/LF or a second address fails it. */
    static @Nullable InternetAddress parseSingleAddress(String address) {
        if (address.isBlank() || address.chars().anyMatch(c -> c == '\r' || c == '\n')) {
            return null;
        }
        try {
            InternetAddress[] parsed = InternetAddress.parse(address, true);
            if (parsed.length != 1 || parsed[0].isGroup()) {
                return null;
            }
            parsed[0].validate();
            return parsed[0];
        } catch (AddressException e) {
            return null;
        }
    }

    private static String domainOf(String address) {
        int at = address.lastIndexOf('@');
        return at < 0 ? "hephaestus.invalid" : address.substring(at + 1);
    }
}
