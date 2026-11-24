package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.web.LabelDTO;
import de.tum.cit.aet.hephaestus.core.web.MentoringCopy;
import java.io.Serial;

/** The ledger refused a request that conflicts with its state. Nothing was written. */
public final class ConversationRejectedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** The conversation already holds the maximum number of follow-ups waiting for an answer. */
        FOLLOW_UPS_FULL,
        /** The request id belongs to a different conversation or kind of write. */
        REQUEST_ID_REUSED,
        /** Threads start only on a message of a main conversation. */
        NOT_A_THREAD_PARENT,
        /** Votes are only on Hephaestus's messages that are not erased. */
        NOT_VOTABLE,
        /** Only a Slack message the developer wrote can be edited. */
        NOT_EDITABLE,
        NOT_DELETABLE,
        /** The person's data was erased, so Hephaestus stores nothing new about them. */
        PERSON_ERASED;

        public LabelDTO label() {
            return MentoringCopy.label(
                    this == PERSON_ERASED
                            ? "conversation.person-erased"
                            : MentoringCopy.code("conversation.rejected.", this));
        }

        public String userMessage() {
            return label().text();
        }
    }

    private final Reason reason;

    public ConversationRejectedException(Reason reason) {
        super(reason.userMessage());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
