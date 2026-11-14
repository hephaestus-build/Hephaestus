package de.tum.cit.aet.hephaestus.mentor.spi;

import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Snapshots of the turns of the mentor ledger; no returned value is a managed entity. */
public interface MentorTurnAccess {
    Optional<TurnState> find(long workspaceId, UUID turnId);

    record TurnState(
            UUID id, UUID conversationId, MentorTurn.Status status, MentorTurn.Surface surface, Instant createdAt) {}
}
