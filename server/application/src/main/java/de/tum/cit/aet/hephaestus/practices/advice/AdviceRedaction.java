package de.tum.cit.aet.hephaestus.practices.advice;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceErasureListener;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Advice lasts no longer than its evidence (ADR 0160). Every transaction that deletes results calls this first, so the
 * text of each advice revision with one of them as a source goes in the same commit.
 *
 * <p>It locks the results, then the revisions, each in id order. An advice write locks its results in the same order
 * before it inserts, so a write that commits first is redacted here, and a write that waits finds a result gone. Under
 * READ COMMITTED each statement here sees the writes that committed while the lock waited.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class AdviceRedaction {
    private final ResultRepository results;
    private final PracticeAdviceRevisionRepository revisions;
    /** Empty until the conversation side provides one (#5278). */
    private final List<AdviceErasureListener> listeners;

    /** Runs in the caller's deleting transaction, before the delete; returns the revisions that lost their text. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> beforeResultsDeleted(long workspaceId, Collection<UUID> resultIds) {
        if (resultIds.isEmpty()) return List.of();
        var ids = resultIds.stream().distinct().toArray(UUID[]::new);
        results.lockInIdOrder(workspaceId, ids);
        var stale = revisions.lockWithSourcesIn(workspaceId, ids);
        if (stale.isEmpty()) return List.of();
        revisions.redact(workspaceId, stale.toArray(UUID[]::new));
        for (var listener : listeners) listener.staleRevisions(workspaceId, stale);
        return stale;
    }
}
