package de.tum.cit.aet.hephaestus.agent.conversation.turn.exception;

import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationRefusal;
import java.io.Serial;

/** Conversation admission declined the member; the turn stops before anything is persisted. */
public final class ConversationRefusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ConversationRefusal reason;

    public ConversationRefusedException(ConversationRefusal reason) {
        super("Conversation turn refused: " + reason);
        this.reason = reason;
    }

    public ConversationRefusal reason() {
        return reason;
    }
}
