package de.tum.cit.aet.hephaestus.conversation.spi;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Snapshots of the conversations of the conversation ledger and their entries; no returned value is a managed entity. */
public interface ConversationAccess {

    /** The account whose conversation it is. */
    long accountId(long workspaceId, UUID conversationId);

    /** The account whose conversation it is; empty when the workspace has no such conversation. */
    Optional<Long> findAccountId(long workspaceId, UUID conversationId);

    /** The main conversation and all its threads in this workspace. */
    List<UUID> conversationIds(long workspaceId, long accountId);

    /**
     * The user question or the system digest that started the turn.
     *
     * @throws IllegalStateException when the turn has neither
     */
    LedgerEntry stimulus(long workspaceId, UUID turnId);

    /** The digest entry of the turn, also when it is erased. */
    Optional<LedgerEntry> digest(long workspaceId, UUID turnId);

    /** Whether the turn has a message from Hephaestus that is not erased. */
    boolean answered(long workspaceId, UUID turnId);

    record LedgerEntry(
            UUID id,
            ConversationEntry.Kind kind,
            boolean redacted,
            @Nullable JsonNode payload) {}
}
