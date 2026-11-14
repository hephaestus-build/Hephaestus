package de.tum.cit.aet.hephaestus.conversation;

import java.io.Serial;
import java.util.UUID;

/**
 * A host wrote for an attempt that no longer holds the turn: the lease expired, another server claimed the turn, or
 * the attempt already ended. The host must stop working on the turn; nothing it sent was stored.
 */
public final class ConversationLeaseLostException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ConversationLeaseLostException(UUID turnId, int attempt) {
        super("Conversation turn " + turnId + " attempt " + attempt + " no longer holds its lease");
    }
}
