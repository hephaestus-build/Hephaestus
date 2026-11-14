package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.action.ActionVariants;
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
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The SMTP boundary, and the only adapter of {@link ActionKind#EMAIL}. Email has no readback, so each message is sent
 * at most once: a send whose outcome the relay left unclear ends {@link EmailDeliveryResult.Outcome#UNCONFIRMED} and
 * is never sent again. Its Message-ID derives from the action's idempotency key, so a recipient's client can tell one
 * message from another. Delivery policy lives in ADR 0044.
 */
@Component
@OutboundEgressGateway
@ConditionalOnServerRole
public class EmailAdapter implements ActionAdapter {

    private static final Logger log = LoggerFactory.getLogger(EmailAdapter.class);

    static final String EGRESS_OPERATION = "email.send";

    /** The payload names the notice's {@link EmailKind} and holds its event. */
    static final String PAYLOAD_KIND = "kind";

    static final String PAYLOAD_EVENT = "event";

    private final Optional<JavaMailSender> mailSender;
    private final EmailProperties properties;
    private final OutboundEgressGuard egressGuard;
    private final EmailDeliveryMetrics metrics;
    private final EmailRateLimiter rateLimiter;
    private final ObjectMapper mapper;
    private final Map<EmailKind, EmailNotice<?>> notices = new EnumMap<>(EmailKind.class);

    public EmailAdapter(
            Optional<JavaMailSender> mailSender,
            EmailProperties properties,
            OutboundEgressGuard egressGuard,
            EmailDeliveryMetrics metrics,
            EmailRateLimiter rateLimiter,
            ObjectMapper mapper,
            List<EmailNotice<?>> notices) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.egressGuard = egressGuard;
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
        this.mapper = mapper;
        for (EmailNotice<?> notice : notices)
            if (this.notices.put(notice.kind(), notice) != null)
                throw new IllegalStateException("Two notices write " + notice.kind());
    }

    @Override
    public ActionKind kind() {
        return ActionKind.EMAIL;
    }

    /** The kinds whose notice this build has, each with the event that it decodes, enum values included. */
    @Override
    public ActionVariants variants() {
        Map<String, Class<?>> events = new LinkedHashMap<>();
        notices.forEach((kind, notice) -> events.put(kind.name(), notice.eventType()));
        return ActionVariants.tagged(PAYLOAD_KIND, PAYLOAD_EVENT, events);
    }

    @Override
    public ActionResult execute(ActionAttempt attempt) {
        EmailKind kind = EmailKind.valueOf(attempt.payload().path(PAYLOAD_KIND).asString());
        EmailNotice<?> notice = notices.get(kind);
        if (notice == null) throw new IllegalStateException("No notice writes " + kind);
        Delivery delivery = switch (prepare(notice, attempt.payload().path(PAYLOAD_EVENT))) {
            case EmailNotice.Prepared.Withhold withheld -> withheld(withheld.outcome());
            case EmailNotice.Prepared.Send send -> deliver(send.message(), attempt);
        };
        if (delivery.outcome() != null) metrics.record(kind, delivery.outcome());
        return delivery.result();
    }

    /** The message as the notice builds it now: a retry rechecks who still wants it. */
    private <E> EmailNotice.Prepared prepare(EmailNotice<E> notice, JsonNode event) {
        return notice.prepare(mapper.treeToValue(event, notice.eventType()));
    }

    private static Delivery withheld(Outcome outcome) {
        ActionResult result = switch (outcome) {
            case EXPIRED -> new ActionResult.Cancelled(ActionStopReason.EXPIRED);
            case UNSUBSCRIBED -> new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
            case SILENT_MODE -> new ActionResult.Cancelled(ActionStopReason.SILENCED);
            case NOT_CONFIGURED -> new ActionResult.Failed(ActionStopReason.NOT_CONFIGURED);
            case NO_RECIPIENT, INVALID_ADDRESS -> new ActionResult.Failed(ActionStopReason.RECIPIENT_UNAVAILABLE);
            default -> new ActionResult.Failed(ActionStopReason.REJECTED);
        };
        return new Delivery(outcome, result);
    }

    /** The outcome an admin sees for one attempt's result. */
    static Outcome outcome(ActionResult result) {
        return switch (result) {
            case ActionResult.Done done -> Outcome.SENT;
            case ActionResult.Retry retry -> Outcome.UNAVAILABLE;
            case ActionResult.Defer defer -> Outcome.RATE_LIMITED;
            case ActionResult.Uncertain uncertain -> Outcome.UNCONFIRMED;
            case ActionResult.Reread reread -> Outcome.UNCONFIRMED;
            case ActionResult.Cancelled cancelled ->
                switch (cancelled.reason()) {
                    case EXPIRED -> Outcome.EXPIRED;
                    case WITHDRAWN -> Outcome.UNSUBSCRIBED;
                    default -> Outcome.SILENT_MODE;
                };
            case ActionResult.Abandoned abandoned -> Outcome.UNAVAILABLE;
            case ActionResult.Failed failed ->
                switch (failed.reason()) {
                    case NOT_CONFIGURED -> Outcome.NOT_CONFIGURED;
                    case RECIPIENT_UNAVAILABLE -> Outcome.INVALID_ADDRESS;
                    default -> Outcome.REJECTED;
                };
        };
    }

    /** The outcome for the delivery metric, absent when the attempt lost its lease, and the attempt's result. */
    private record Delivery(@Nullable Outcome outcome, ActionResult result) {}

    private Delivery deliver(EmailMessage message, ActionAttempt attempt) {
        JavaMailSender sender = mailSender.orElse(null);
        String from = properties.from();
        if (sender == null || from == null || !properties.hasFrom()) {
            log.debug("email: not configured, withheld kind={}", message.kind().tag());
            return new Delivery(Outcome.NOT_CONFIGURED, new ActionResult.Failed(ActionStopReason.NOT_CONFIGURED));
        }
        InternetAddress to = parseSingleAddress(message.to());
        if (to == null) {
            log.warn(
                    "email: recipient address invalid, withheld kind={}",
                    message.kind().tag());
            return new Delivery(
                    Outcome.INVALID_ADDRESS, new ActionResult.Failed(ActionStopReason.RECIPIENT_UNAVAILABLE));
        }
        try {
            egressGuard.requireDeliveryAllowed(EGRESS_OPERATION);
        } catch (OutboundEgressSuppressedException e) {
            log.info(
                    "email: Silent Mode engaged, withheld kind={}",
                    message.kind().tag());
            return new Delivery(Outcome.SILENT_MODE, new ActionResult.Cancelled(ActionStopReason.SILENCED));
        }

        Optional<Duration> refill = rateLimiter.acquire(message.kind().optional());
        if (refill.isPresent()) {
            return new Delivery(Outcome.RATE_LIMITED, new ActionResult.Defer(refill.get()));
        }

        String messageId = messageId(attempt.idempotencyKey(), from);
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
            return new Delivery(Outcome.REJECTED, new ActionResult.Failed(ActionStopReason.REJECTED));
        }
        if (!attempt.fence().beginWrite()) {
            return new Delivery(null, new ActionResult.Abandoned());
        }
        try {
            sender.send(mimeMessage);
        } catch (MailException e) {
            return failed(message.kind(), messageId, attempt.attempt(), e);
        }
        log.info("email: sent kind={} messageId={}", message.kind().tag(), messageId);
        return new Delivery(Outcome.SENT, new ActionResult.Done(messageId));
    }

    /** One message's stable Message-ID: the same action never names two messages, and two actions never share one. */
    static String messageId(String idempotencyKey, String from) {
        return "<" + UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8)) + "@" + domainOf(from)
                + ">";
    }

    private static Delivery failed(EmailKind kind, String messageId, int attempt, MailException e) {
        Delivery delivery = classify(e, attempt);
        Throwable root = e.getMostSpecificCause();
        // The exception message can quote the recipient and the relay's reply; the class names are enough
        // to tell "connection refused" from "authentication failed" from "address rejected".
        log.warn(
                "email: delivery {} kind={} messageId={} cause={} rootCause={}",
                Objects.requireNonNull(delivery.outcome()).name().toLowerCase(Locale.ROOT),
                kind.tag(),
                messageId,
                e.getClass().getSimpleName(),
                root.getClass().getName());
        return delivery;
    }

    /**
     * Whether the relay can hold the message. Only a failure that proves it does not is retried: the connection or
     * the login failed, or the relay answered with an SMTP reply code. Any other failure after the message was handed
     * to the transport leaves its delivery unknown.
     */
    private static Delivery classify(MailException e, int attempt) {
        var retry = new Delivery(Outcome.UNAVAILABLE, new ActionResult.Retry(ActionExecutor.backoff(attempt)));
        var rejected = new Delivery(Outcome.REJECTED, new ActionResult.Failed(ActionStopReason.REJECTED));
        if (e instanceof MailAuthenticationException) {
            return retry;
        }
        if (e instanceof MailSendException sendException) {
            Map<Object, Exception> failed = sendException.getFailedMessages();
            // A connection failure is the exception's cause and every message's failure, by identity.
            Throwable cause = sendException.getCause();
            if (cause != null && failed.values().stream().anyMatch(failure -> same(failure, cause))) {
                return retry;
            }
            for (Exception failure : sendException.getMessageExceptions()) {
                if (isPermanentFailure(failure)) {
                    return rejected;
                }
                if (hasReplyCode(failure)) {
                    return retry;
                }
            }
        }
        return new Delivery(Outcome.UNCONFIRMED, new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED));
    }

    private static boolean hasReplyCode(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (replyCode(current) >= 400) {
                return true;
            }
            if (causesItself(current)) {
                break;
            }
        }
        return false;
    }

    private static int replyCode(Throwable failure) {
        return switch (failure) {
            case SMTPSendFailedException smtp -> smtp.getReturnCode();
            case SMTPSenderFailedException smtp -> smtp.getReturnCode();
            case SMTPAddressFailedException smtp -> smtp.getReturnCode();
            default -> 0;
        };
    }

    private static boolean isPermanentFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            int code = replyCode(current);
            if (code >= 400 && code < 600) {
                return code >= 500;
            }
            if (current instanceof SendFailedException sendFailed
                    && sendFailed.getInvalidAddresses() != null
                    && sendFailed.getInvalidAddresses().length > 0) {
                return true;
            }
            if (causesItself(current)) {
                break;
            }
        }
        return false;
    }

    @SuppressWarnings("ReferenceEquality") // the connection failure is the same object, not an equal one
    private static boolean same(Throwable failure, Throwable cause) {
        return failure == cause;
    }

    @SuppressWarnings("ReferenceEquality") // cycle guard: a Jakarta Mail next-exception chain can point at itself
    private static boolean causesItself(Throwable failure) {
        return failure.getCause() == failure;
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
