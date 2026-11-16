package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends notification email through the outbox. A notice that is ready now enqueues its event once per recipient;
 * the executor prepares the message again and sends it at most once ({@link EmailAdapter}).
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class Emails {
    /** Attempts that the relay refused before accepting; with the backoff they span most of a day. */
    static final int MAX_ATTEMPTS = 24;

    private final Optional<JavaMailSender> mailSender;
    private final EmailProperties properties;
    private final AgentActions actions;
    private final EmailDeliveryMetrics metrics;
    private final ObjectMapper mapper;

    /** Whether this instance can send email at all: a relay host and a sender address are both set. */
    public boolean configured() {
        return mailSender.isPresent() && properties.hasFrom();
    }

    /** Enqueues the email the event asks for, or records why it is withheld. */
    public <E> void enqueue(EmailNotice<E> notice, E event) {
        if (notice.prepare(event) instanceof EmailNotice.Prepared.Withhold withheld) {
            metrics.record(notice.kind(), withheld.outcome());
            return;
        }
        actions.enqueue(request(notice, event, MAX_ATTEMPTS));
    }

    /** The action that sends the email the event asks for. */
    <E> ActionRequest request(EmailNotice<E> notice, E event, int maxAttempts) {
        EmailNotice.Address address = notice.address(event);
        String idempotencyKey = "email:" + notice.kind().tag() + ":" + address.key();
        return new ActionRequest(
                ActionKind.EMAIL,
                address.workspaceId(),
                idempotencyKey,
                idempotencyKey,
                mapper.createObjectNode()
                        .put(EmailAdapter.PAYLOAD_KIND, notice.kind().name())
                        .set(EmailAdapter.PAYLOAD_EVENT, mapper.valueToTree(event)),
                address.recipientAccountId(),
                maxAttempts,
                address.expiresAt(),
                null);
    }
}
