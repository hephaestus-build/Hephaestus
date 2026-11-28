package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.practices.spi.AdviceRevisionTexts;
import java.util.Collection;
import java.util.List;
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
 * <p>A basis can also read an advice revision ({@link BasisAdviceEdge}): the conversation read that hands advice to an
 * attempt binds its exact revision id ({@link #bindAdvice}) before the advice leaves. When the revision loses its text
 * to an erasure or a withdrawal, {@link #entriesThatReadAdvice} finds what the attempts stored, and an attempt still
 * running stores nothing that the advice could shape ({@link #lostSource}).
 *
 * <p>The documents archive, document fetches, {@code load_context} and the situation are not bound yet: their copies
 * still follow the copying tool results ({@link ConversationEntryRepository#COPIES_PERSON_DATA}).
 */
@Component
@RequiredArgsConstructor
public class ConversationBasis {

    private final BasisEdgeRepository edges;
    private final BasisAdviceEdgeRepository adviceEdges;
    private final ConversationEntryRepository entries;
    private final AdviceRevisionTexts adviceTexts;

    /** Binds these entries to the basis. The caller's transaction read them and holds a lock of their tree. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(long workspaceId, UUID basisId, Collection<UUID> entryIds) {
        if (!entryIds.isEmpty()) {
            edges.bindEntries(workspaceId, basisId, entryIds.toArray(UUID[]::new));
        }
    }

    /**
     * Binds these advice revisions to the basis. The caller's transaction locked them with {@link
     * AdviceRevisionTexts#lockWithText}, which returned them, then locked the attempt's tree ({@code FOR SHARE}), as the
     * redaction and its listener lock them in that order, and read them under both. An erasure that waits finds this
     * row, and one that committed first left no text to read.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bindAdvice(long workspaceId, UUID basisId, Collection<UUID> lockedRevisionIds) {
        if (!lockedRevisionIds.isEmpty()) {
            adviceEdges.bindRevisions(workspaceId, basisId, lockedRevisionIds.toArray(UUID[]::new));
        }
    }

    /**
     * Whether a source of the basis was erased after the attempt read it: an entry, or an advice revision that lost its
     * text. The caller holds a lock of its tree.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lostSource(long workspaceId, UUID basisId) {
        return edges.hasErasedEntry(workspaceId, basisId)
                || adviceTexts.anyWithoutText(workspaceId, adviceEdges.revisionsOf(workspaceId, basisId));
    }

    /**
     * The entries whose basis read one of these advice revisions, erased ones too. It first locks the tree of every turn
     * whose attempt read one, in id order, as a redaction does, so an attempt that ends meanwhile either stored its
     * entries before this read or finds the revision without text ({@link #lostSource}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> entriesThatReadAdvice(long workspaceId, Collection<UUID> revisionIds) {
        if (revisionIds.isEmpty()) return List.of();
        UUID[] ids = revisionIds.toArray(UUID[]::new);
        entries.lockTreesOfAdviceReaders(workspaceId, ids);
        return adviceEdges.findEntriesThatRead(workspaceId, ids);
    }
}
