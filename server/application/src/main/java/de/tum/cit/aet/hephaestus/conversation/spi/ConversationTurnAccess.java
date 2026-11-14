package de.tum.cit.aet.hephaestus.conversation.spi;

import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Snapshots of the turns of the conversation ledger; no returned value is a managed entity. */
public interface ConversationTurnAccess {
    Optional<TurnState> find(long workspaceId, UUID turnId);

    record TurnState(
            UUID id,
            UUID conversationId,
            ConversationTurn.Status status,
            ConversationTurn.Surface surface,
            Instant createdAt) {}
}
