package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One kind of notification email, built from the event that asks for it. The outbox stores only the event, and
 * {@link #prepare} runs again before every attempt: an email whose recipient unsubscribed, lost the role or the
 * address, or whose source is gone, is withheld on the retry as it would have been at first.
 *
 * @param <E> the event, stored as JSON in the action, so it holds identifiers and no message text
 */
public interface EmailNotice<E> {

    /** The one kind of email this notice writes. */
    EmailKind kind();

    Class<E> eventType();

    /** Who the email is for, under which natural key, and until when it is worth sending. */
    Address address(E event);

    /** The message to send now, or why it is withheld. Reads the current state; never writes. */
    Prepared prepare(E event);

    /**
     * @param key the natural key of this notification to this recipient; the same key enqueues nothing new
     * @param recipientAccountId the account the email addresses; its erasure deletes the email
     * @param workspaceId the workspace the email is about, or {@code null} for an account or instance notice
     * @param expiresAt when the email is no news any more, if it has not been sent by then
     */
    record Address(
            String key, long recipientAccountId, @Nullable Long workspaceId, Instant expiresAt) {}

    sealed interface Prepared {
        record Send(EmailMessage message) implements Prepared {}

        record Withhold(Outcome outcome) implements Prepared {}

        static Prepared send(EmailMessage message) {
            return new Send(message);
        }

        static Prepared withhold(Outcome outcome) {
            return new Withhold(outcome);
        }
    }
}
