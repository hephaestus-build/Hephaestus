package de.tum.cit.aet.hephaestus.mentor;

import java.io.Serial;

/** The ledger refused a request that conflicts with its state. Nothing was written. */
public final class MentorRejectedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** The conversation already holds the maximum number of follow-ups waiting for an answer. */
        FOLLOW_UPS_FULL("Heph is still answering. Wait for an answer before you send more messages."),
        /** The request id belongs to a different conversation or kind of write. */
        REQUEST_ID_REUSED("This message was already sent somewhere else."),
        /** Threads start only on a message of a main conversation. */
        NOT_A_THREAD_PARENT("You can reply in a thread only to a message in your conversation with Heph."),
        /** Votes are only on Heph's messages that are not erased. */
        NOT_VOTABLE("You can rate only Heph's messages that are still in your conversation."),
        /** Only a Slack message the developer wrote can be edited. */
        NOT_EDITABLE("This message cannot be edited."),
        NOT_DELETABLE("You can delete only your own messages."),
        /** The developer's personal data was erased, so Heph stores nothing new about them. */
        PERSON_ERASED("Heph cannot reply because your personal data was erased. Contact the instance privacy contact.");

        private final String userMessage;

        Reason(String userMessage) {
            this.userMessage = userMessage;
        }

        public String userMessage() {
            return userMessage;
        }
    }

    private final Reason reason;

    public MentorRejectedException(Reason reason) {
        super(reason.userMessage());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
