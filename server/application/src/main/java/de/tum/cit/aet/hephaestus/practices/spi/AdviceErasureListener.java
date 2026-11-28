package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Collection;
import java.util.UUID;

/**
 * Hears which advice revisions lost their text because evidence behind them was deleted, in the deleting transaction.
 * The ids are revision ids. The conversation side ({@code ConversationAdviceErasure}) uses them to reach the answers
 * whose basis read those revisions, and a failure rolls the deletion back. Without this bean, advice is written only
 * where {@code hephaestus.practices.advice.allow-without-erasure-listener} is set, which synthetic proof environments
 * alone do.
 */
public interface AdviceErasureListener {
    void staleRevisions(long workspaceId, Collection<UUID> revisionIds, AdviceStaleCause cause);
}
