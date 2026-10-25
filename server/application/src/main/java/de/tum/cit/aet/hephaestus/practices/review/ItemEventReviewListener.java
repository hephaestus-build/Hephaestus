package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventRecorded;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemSourceObserved;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.practices.result.ResultCurrentness;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ItemEventReviewListener {
    private final ItemEventService events;
    private final ProductItemQuery items;
    private final WorkspaceRepository workspaces;
    private final ReviewService reviews;
    private final ResultCurrentness currentness;
    private final ResultStore results;

    @ApplicationModuleListener
    public void on(ItemEventRecorded recorded) {
        if (workspaces.findByIdForUpdate(recorded.workspaceId()).isEmpty())
            throw new IllegalStateException("The event workspace no longer exists");
        var event = events.find(recorded.workspaceId(), recorded.eventId()).orElseThrow();
        if (event.name().value().startsWith("scm.")) {
            currentness.reconcile(recorded.workspaceId(), recorded.itemId());
        }
        var state = event.state();
        if (state.isTerminal()) return;
        var item = items.find(recorded.workspaceId(), recorded.itemId());
        if (state == ItemEventState.RECORDED && event.name().value().startsWith("scm.pull_request_review.")) {
            results.endCurrent(
                    recorded.workspaceId(),
                    recorded.itemId(),
                    item.isEmpty() ? ResultEndReason.REMOVED : ResultEndReason.WORK_CHANGED,
                    event.occurredAt());
        }
        if (item.isEmpty()) {
            decide(recorded, state, ItemEventState.WITHHELD, ReviewRequestReason.ITEM_GONE);
            return;
        }
        // The source pins the version later and offers the occasion again; the expiry bounds the wait.
        if (event.itemVersion() == null) return;
        var version = new ItemVersion(event.itemVersion(), null);
        try {
            reviews.request(ReviewRequest.builder()
                    .workspaceId(recorded.workspaceId())
                    .itemId(recorded.itemId())
                    .kind(ReviewKind.ITEM)
                    .version(version)
                    .origin(ReviewOrigin.EVENT)
                    .eventId(recorded.eventId())
                    .practiceIds(List.of())
                    .build());
            decide(recorded, state, ItemEventState.STARTED, null);
        } catch (ReviewRequestRejected refusal) {
            ReviewRequestReason reason = switch (refusal.reason()) {
                case WORKSPACE_INACTIVE -> ReviewRequestReason.WORKSPACE_INACTIVE;
                case BUDGET_UNAVAILABLE -> ReviewRequestReason.BUDGET_EXHAUSTED;
                case MODEL_UNAVAILABLE -> ReviewRequestReason.MODEL_UNAVAILABLE;
                case SOURCE_UNAVAILABLE ->
                    throw new IllegalStateException("Only a scan can lack a default branch", refusal);
                case ITEM_INELIGIBLE ->
                    Objects.requireNonNullElse(refusal.eventReason(), ReviewRequestReason.NO_ACTIVE_PRACTICE);
                case REQUEST_CONFLICT -> ReviewRequestReason.VERSION_SUPERSEDED;
                case SCAN_ACTIVE, REF_UNSUPPORTED, VERSION_CHANGED ->
                    throw new IllegalStateException("An item review cannot compete with a scan", refusal);
            };
            boolean retry = refusal.reason().transientReason();
            decide(recorded, state, retry ? ItemEventState.WAITING : ItemEventState.WITHHELD, reason);
        }
    }

    @ApplicationModuleListener
    public void on(ItemSourceObserved observed) {
        if (workspaces.findByIdForUpdate(observed.workspaceId()).isEmpty())
            throw new IllegalStateException("The event workspace no longer exists");
        currentness.reconcile(observed.workspaceId(), observed.itemId());
    }

    private void decide(
            ItemEventRecorded event, ItemEventState from, ItemEventState to, @Nullable ReviewRequestReason reason) {
        if (!events.decide(event.workspaceId(), event.eventId(), from, to, reason))
            throw new IllegalStateException("The event changed while the workspace was locked");
    }
}
