package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemQuery;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ResultCurrentness {
    private final ItemIndexService itemLocks;
    private final ResultFreshness freshness;
    private final ProductItemQuery items;
    private final ResultRepository results;
    private final Clock clock;

    public void reconcile(long workspaceId, long itemId) {
        itemLocks.lock(workspaceId, itemId);
        var current = results.findByWorkspaceIdAndItemIdAndEndedAtIsNull(workspaceId, itemId);
        if (current.isEmpty()) return;
        reconcile(workspaceId, current, clock.instant());
    }

    public @Nullable ResultEndReason prepareClaim(long workspaceId, Result result, Instant at) {
        itemLocks.lock(workspaceId, result.getItemId());
        reconcile(workspaceId, results.findByWorkspaceIdAndItemIdAndEndedAtIsNull(workspaceId, result.getItemId()), at);
        if (items.find(workspaceId, result.getItemId()).isEmpty()) return ResultEndReason.REMOVED;
        return freshness.states(workspaceId, List.of(result)).get(result.getId()) == Currentness.STALE
                ? ResultEndReason.WORK_CHANGED
                : null;
    }

    private void reconcile(long workspaceId, List<Result> current, Instant at) {
        var states = freshness.states(workspaceId, current);
        boolean changed = false;
        for (var result : current) {
            if (states.get(result.getId()) == Currentness.STALE) {
                result.end(
                        items.find(workspaceId, result.getItemId()).isEmpty()
                                ? ResultEndReason.REMOVED
                                : ResultEndReason.WORK_CHANGED,
                        at.isBefore(result.getRecordedAt()) ? result.getRecordedAt() : at);
                changed = true;
            }
        }
        if (changed) results.flush();
    }
}
