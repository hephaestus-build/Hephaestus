package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Collection;
import java.util.UUID;

/**
 * Hears which advice revisions lost their text because evidence behind them was deleted, in the deleting transaction.
 * The conversation side uses it to reach the answers that quoted them. Without this bean, nothing writes advice.
 */
public interface AdviceErasureListener {
    void staleRevisions(long workspaceId, Collection<UUID> revisionIds);
}
