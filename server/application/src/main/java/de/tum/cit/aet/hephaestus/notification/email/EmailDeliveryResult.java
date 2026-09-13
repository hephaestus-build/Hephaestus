package de.tum.cit.aet.hephaestus.notification.email;

import org.jspecify.annotations.Nullable;

/**
 * What became of one {@link EmailMessage}. Every outcome is a value, not an exception: the caller
 * records it and decides whether to retry, and a withheld email is never mistaken for a sent one.
 *
 * @param outcome the bounded outcome
 * @param messageId the RFC 5322 {@code Message-ID} the relay accepted; only present for {@link Outcome#SENT}
 */
public record EmailDeliveryResult(Outcome outcome, @Nullable String messageId) {

    public enum Outcome {
        /** The relay accepted the message. */
        SENT,
        /** The notification is no longer timely; nothing was sent. */
        EXPIRED,
        /** No {@code spring.mail.host} or no {@code hephaestus.email.from}: the instance does not send email. */
        NOT_CONFIGURED,
        /** Silent Mode is engaged; nothing leaves the instance. */
        SILENT_MODE,
        /** The account has no provider-verified address, so there is nobody to write to. */
        NO_RECIPIENT,
        /** The recipient has disabled this optional notification kind. */
        UNSUBSCRIBED,
        /** The recipient address is not a syntactically valid single mailbox. */
        INVALID_ADDRESS,
        /** The relay classified the address as invalid; no automatic retry. */
        REJECTED,
        /** The relay could not be reached or refused the credentials; a later attempt may succeed. */
        UNAVAILABLE,
        /** Shared relay attempt budget exhausted or unavailable; retry later. */
        RATE_LIMITED;

        /** Only a transport that may recover is worth another attempt. */
        public boolean retryable() {
            return this == UNAVAILABLE || this == RATE_LIMITED;
        }
    }

    public static EmailDeliveryResult sent(String messageId) {
        return new EmailDeliveryResult(Outcome.SENT, messageId);
    }

    public static EmailDeliveryResult of(Outcome outcome) {
        return new EmailDeliveryResult(outcome, null);
    }
}
