package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ResultCurrentness {
    private final ItemIndexService itemLocks;
    private final ProductItemQuery sources;
    private final ResultRepository results;
    private final Clock clock;

    public void reconcile(long workspaceId, long itemId) {
        itemLocks.lock(workspaceId, itemId);
        var current = results.findByWorkspaceIdAndItemIdAndEndedAtIsNull(workspaceId, itemId);
        if (current.isEmpty()) return;
        reconcile(sources.currentWorkVersion(workspaceId, itemId), current, clock.instant());
    }

    public @Nullable ResultEndReason prepareClaim(long workspaceId, long itemId, String version, Instant at) {
        itemLocks.lock(workspaceId, itemId);
        var source = sources.currentWorkVersion(workspaceId, itemId);
        reconcile(source, results.findByWorkspaceIdAndItemIdAndEndedAtIsNull(workspaceId, itemId), at);
        return switch (source) {
            case ProductItemQuery.CurrentWorkVersion.Known known ->
                known.value().equals(version) ? null : ResultEndReason.WORK_CHANGED;
            case ProductItemQuery.CurrentWorkVersion.Removed.INSTANCE -> ResultEndReason.REMOVED;
            case ProductItemQuery.CurrentWorkVersion.Unknown.INSTANCE -> null;
        };
    }

    private void reconcile(ProductItemQuery.CurrentWorkVersion source, List<Result> current, Instant at) {
        boolean changed = false;
        for (var result : current) {
            ResultEndReason reason = switch (source) {
                case ProductItemQuery.CurrentWorkVersion.Known known ->
                    Objects.equals(known.value(), result.getItemVersion()) ? null : ResultEndReason.WORK_CHANGED;
                case ProductItemQuery.CurrentWorkVersion.Removed.INSTANCE -> ResultEndReason.REMOVED;
                case ProductItemQuery.CurrentWorkVersion.Unknown.INSTANCE -> null;
            };
            if (reason != null) {
                result.end(reason, at.isBefore(result.getRecordedAt()) ? result.getRecordedAt() : at);
                changed = true;
            }
        }
        if (changed) results.flush();
    }
}
