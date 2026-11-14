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
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
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

    /**
     * The open results of the item whose captured sources changed. It runs with no transaction because a source read
     * can fetch from the provider; {@link #end} ends them.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Set<UUID> stale(long workspaceId, long itemId) {
        var current = results.findByWorkspaceIdAndItemIdAndEndedAtIsNull(workspaceId, itemId);
        var states = freshness.states(workspaceId, current);
        return current.stream()
                .map(Result::getId)
                .filter(id -> states.get(id) == Currentness.STALE)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Ends each result of {@code stale} that is still open, under the item lock. */
    public void end(long workspaceId, long itemId, Set<UUID> stale) {
        if (stale.isEmpty()) return;
        itemLocks.lock(workspaceId, itemId);
        end(
                workspaceId,
                results.findByWorkspaceIdAndItemIdAndEndedAtIsNull(workspaceId, itemId),
                stale::contains,
                clock.instant());
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
        end(workspaceId, current, id -> states.get(id) == Currentness.STALE, at);
    }

    private void end(long workspaceId, List<Result> current, Predicate<UUID> stale, Instant at) {
        boolean changed = false;
        for (var result : current) {
            if (stale.test(result.getId())) {
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
