package de.tum.cit.aet.hephaestus.integration.core.handler;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.PayloadParsingException;
import io.nats.client.Message;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reusable scaffolding for {@link IntegrationMessageHandler}. Centralises subject-suffix
 * validation (including the GitLab {@code tag_push} vs {@code push} overlap guard),
 * Jackson deserialization, and a {@link TransactionTemplate} boundary that survives
 * Spring's self-invocation limitation.
 *
 * <p>{@code eventType} is the registry index. For GitHub it carries a tier prefix
 * ({@code "repository.issues"}) that the {@code GithubSubjectParser} folds in; the raw
 * subject only carries the token after the last {@code '.'}. The base validates the
 * inbound subject's last segment against that derived token by exact equality.
 *
 * @param <T> DTO type of the deserialized webhook payload.
 */
public abstract class AbstractIntegrationMessageHandler<T> implements IntegrationMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(AbstractIntegrationMessageHandler.class);

    private final EventTypeKey key;
    private final String eventType;
    /**
     * The raw last-segment of the subject this handler accepts (e.g. {@code "issues"}
     * for GitHub's {@code repository.issues} key, {@code "merge_request"} for GitLab).
     * This is what the producer actually puts on the wire — the per-kind
     * {@code SubjectParser} re-encodes it into the prefixed registry key, but the
     * subject itself never carries the tier prefix.
     */
    private final String subjectEventToken;

    private final Class<T> payloadType;
    private final NatsMessageDeserializer deserializer;
    private final TransactionTemplate transactionTemplate;

    /**
     * @param kind                the integration this handler belongs to; folded into
     *                            {@link #key()}.
     * @param eventType           the eventType portion of {@link EventTypeKey}. For
     *                            GitHub this is {@code "<tier>.<event>"} (e.g.
     *                            {@code "repository.issues"}); for GitLab it is the
     *                            flat event token (e.g. {@code "merge_request"}).
     * @param payloadType         {@code Class<T>} for Jackson deserialization.
     * @param deserializer        shared {@link NatsMessageDeserializer} bean.
     * @param transactionTemplate the framework's {@link TransactionTemplate} (the
     *                            handler runs DB writes; we need a real tx boundary).
     */
    protected AbstractIntegrationMessageHandler(
            @org.jspecify.annotations.Nullable IntegrationKind kind,
            String eventType,
            Class<T> payloadType,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (payloadType == null) {
            throw new IllegalArgumentException("payloadType must not be null");
        }
        this.key = new EventTypeKey(kind, eventType);
        this.eventType = eventType;
        // Derive the subject-side event token by stripping any tier prefix encoded in
        // the eventType. GitHub eventType is "<tier>.<event>"; the SubjectParser folds
        // the tier in but the raw subject only carries <event>. GitLab eventType has
        // no prefix so this is a passthrough.
        int lastDot = eventType.lastIndexOf('.');
        this.subjectEventToken = lastDot >= 0 ? eventType.substring(lastDot + 1) : eventType;
        if (this.subjectEventToken.isEmpty()) {
            throw new IllegalArgumentException(
                    "eventType last segment must not be empty (eventType=" + eventType + ")");
        }
        this.payloadType = payloadType;
        this.deserializer = deserializer;
        this.transactionTemplate = transactionTemplate;
    }

    // Not final: CGLIB cannot override final methods on Spring-proxied beans.

    @Override
    public EventTypeKey key() {
        return key;
    }

    @Override
    public void onMessage(Message msg) {
        String subject = msg.getSubject();
        String safeSubject = sanitizeForLog(subject);
        if (!subjectMatchesExpectedEvent(subject)) {
            log.error(
                    "Rejected message: reason=unexpectedSubject, subject={}, expectedEventType={}",
                    safeSubject,
                    eventType);
            return;
        }

        try {
            T eventPayload = deserializer.deserialize(msg, payloadType);
            dispatchEvent(eventPayload);
        } catch (IOException e) {
            log.error("Failed to parse payload: subject={}", safeSubject, e);
            throw new PayloadParsingException("Payload parsing failed for subject: " + safeSubject, e);
        }
        // Other exceptions intentionally propagate so the consumer dispatcher can
        // decide between ACK / NACK / dead-letter. We do not log them here to avoid
        // duplicate logging.
    }

    /** Override when external preparation must run before short persistence transactions. */
    protected void dispatchEvent(T eventPayload) {
        transactionTemplate.executeWithoutResult(status -> handleEvent(eventPayload));
    }

    /**
     * Handles the deserialized payload. The default {@link #dispatchEvent(Object)} wraps
     * this call in a transaction; orchestration handlers own their persistence boundaries.
     */
    protected abstract void handleEvent(T eventPayload);

    /**
     * Subject-matching rule. Compares the trailing segment of the subject against the
     * raw {@link #subjectEventToken} via last-dot extraction — exactly matching the
     * GitLab legacy base's anti-{@code endsWith}-overlap guard. {@code "tag_push"}
     * cannot pass as {@code "push"} because the last-segment comparison requires the
     * full token to align.
     */
    private boolean subjectMatchesExpectedEvent(String subject) {
        if (subject == null) {
            return false;
        }
        int lastDot = subject.lastIndexOf('.');
        String lastSegment = lastDot >= 0 ? subject.substring(lastDot + 1) : subject;
        return subjectEventToken.equals(lastSegment);
    }
}
