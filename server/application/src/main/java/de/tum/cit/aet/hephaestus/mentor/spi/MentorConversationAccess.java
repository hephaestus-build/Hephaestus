package de.tum.cit.aet.hephaestus.mentor.spi;

import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Snapshots of the conversations of the mentor ledger and their entries; no returned value is a managed entity. */
public interface MentorConversationAccess {

    /** The account whose conversation it is. */
    long accountId(long workspaceId, UUID conversationId);

    /**
     * The user question or the system digest that started the turn.
     *
     * @throws IllegalStateException when the turn has neither
     */
    LedgerEntry stimulus(long workspaceId, UUID turnId);

    /** The digest entry of the turn, also when it is erased. */
    Optional<LedgerEntry> digest(long workspaceId, UUID turnId);

    /** Whether the turn has a message from Heph that is not erased. */
    boolean answered(long workspaceId, UUID turnId);

    record LedgerEntry(
            UUID id,
            MentorEntry.Kind kind,
            boolean redacted,
            @Nullable JsonNode payload) {}
}
