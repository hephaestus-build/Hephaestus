package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.authority.spi.AuthorityGenerations;
import de.tum.cit.aet.hephaestus.core.authority.spi.CheckedAuthority;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
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
    private final AuthorityGenerations generations;

    /** Whether this instance can send email at all: a relay host and a sender address are both set. */
    public boolean configured() {
        return mailSender.isPresent() && properties.hasFrom();
    }

    /**
     * Enqueues the email the event asks for, or records why it is withheld. The notice's preparation decides whether
     * its recipient may still get it: a workspace's email reads the authorization stamp before it (ADR 0134).
     */
    public <E> void enqueue(EmailNotice<E> notice, E event) {
        var withheld = new AtomicReference<EmailNotice.Prepared.@Nullable Withhold>();
        BooleanSupplier ready = () -> {
            if (!(notice.prepare(event) instanceof EmailNotice.Prepared.Withhold refused)) return true;
            withheld.set(refused);
            return false;
        };
        EmailNotice.Address address = notice.address(event);
        Long workspace = address.workspaceId();
        List<Long> people = notice.dependsOnRecipient() ? List.of(address.recipientAccountId()) : List.of();
        Optional<CheckedAuthority> authority =
                workspace == null ? Optional.empty() : generations.authorize(workspace, people, ready);
        if (workspace == null ? !ready.getAsBoolean() : authority.isEmpty()) {
            metrics.record(notice.kind(), Objects.requireNonNull(withheld.get()).outcome());
            return;
        }
        actions.enqueue(request(notice, event, authority.orElse(null), MAX_ATTEMPTS));
    }

    /**
     * The action that sends the email the event asks for.
     *
     * @param authority the authority its preparation granted; NULL for an email that belongs to no workspace
     */
    <E> ActionRequest request(EmailNotice<E> notice, E event, @Nullable CheckedAuthority authority, int maxAttempts) {
        EmailNotice.Address address = notice.address(event);
        if (!Objects.equals(address.workspaceId(), authority == null ? null : authority.workspaceId()))
            throw new IllegalArgumentException("An email carries the authority of its own workspace");
        String idempotencyKey = "email:" + notice.kind().tag() + ":" + address.key();
        return new ActionRequest(
                ActionKind.EMAIL,
                authority,
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
