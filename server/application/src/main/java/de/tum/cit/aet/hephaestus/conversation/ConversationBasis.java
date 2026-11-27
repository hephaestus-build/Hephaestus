package de.tum.cit.aet.hephaestus.conversation;

import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The basis of a turn's attempt (ADR 0128): each conversation entry whose words the server released to the attempt, at
 * the revision it released. The entries that the attempt stores carry its basis id, so they derive from every source
 * of the basis.
 *
 * <ul>
 *   <li>The server binds a source in the transaction that reads it, and that transaction commits before the bytes
 *       leave: the turn's context ({@link ConversationContextPathBuilder#forTurn}) and each {@code search_conversation}
 *       call ({@link ConversationEntryService#searchForTurn}). Both read under a shared lock of the tree's main
 *       conversation row, which every redaction locks first ({@link ConversationRedaction}).
 *   <li>A removal that takes back a source erases every entry whose basis holds it, and every entry derived from those,
 *       in its own transaction ({@link ConversationEntryRepository#redactWithCopies}).
 *   <li>An attempt that ends after a source of its basis was erased stores nothing that the source could shape
 *       ({@link ConversationTurnService#end}).
 * </ul>
 *
 * <p>The documents archive, document fetches, {@code load_context} and the situation are not bound yet: their copies
 * still follow the copying tool results ({@link ConversationEntryRepository#COPIES_PERSON_DATA}).
 */
@Component
@RequiredArgsConstructor
public class ConversationBasis {

    private final BasisEdgeRepository edges;

    /** Binds these entries to the basis. The caller's transaction read them and holds a lock of their tree. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(long workspaceId, UUID basisId, Collection<UUID> entryIds) {
        if (!entryIds.isEmpty()) {
            edges.bindEntries(workspaceId, basisId, entryIds.toArray(UUID[]::new));
        }
    }

    /** Whether a source of the basis was erased after the attempt read it. The caller holds a lock of its tree. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lostSource(long workspaceId, UUID basisId) {
        return edges.hasErasedEntry(workspaceId, basisId);
    }
}
