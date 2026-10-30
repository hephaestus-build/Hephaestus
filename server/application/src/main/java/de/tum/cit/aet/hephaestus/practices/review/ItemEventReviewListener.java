package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventRecorded;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemSourceObserved;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.practices.PracticeItemOptions;
import de.tum.cit.aet.hephaestus.practices.result.ResultCurrentness;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private final PracticeItemOptions itemOptions;
    private final Clock clock;

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
            decide(
                    recorded.workspaceId(),
                    recorded.eventId(),
                    state,
                    ItemEventState.WITHHELD,
                    ReviewRequestReason.ITEM_GONE);
            return;
        }
        // The source pins the version later and offers the occasion again; the expiry bounds the wait.
        if (event.itemVersion() == null) return;
        var kind = item.orElseThrow().kind();
        var open = events.open(recorded.workspaceId(), recorded.itemId());
        if (!itemOptions.occasion(kind, event.name()).joinsWindow()) {
            open.stream()
                    .filter(waiting -> waiting.id().equals(recorded.eventId()))
                    .findFirst()
                    .ifPresent(self -> edge(recorded.workspaceId(), recorded.itemId(), List.of(self)));
            return;
        }
        var window = open.stream()
                .filter(waiting -> itemOptions.occasion(kind, waiting.name()).joinsWindow())
                .toList();
        if (window.isEmpty()) return;
        var closes = events.windowCloses(window);
        if (closes.isAfter(clock.instant())) {
            events.hold(recorded.workspaceId(), recorded.itemId(), window, ReviewRequestReason.QUIET_WINDOW, closes);
            return;
        }
        edge(recorded.workspaceId(), recorded.itemId(), window);
    }

    /**
     * The readiness edge of a window: one review at its newest occasion runs what every occasion of the window selects
     * and is ready, and the older occasions are coalesced into it. A practice that an earlier review of the item still
     * measures holds the window until that review ends.
     */
    private void edge(long workspaceId, long itemId, List<ItemEventService.Open> window) {
        var newest = window.getLast();
        try {
            reviews.request(ReviewRequest.builder()
                    .workspaceId(workspaceId)
                    .itemId(itemId)
                    .kind(ReviewKind.ITEM)
                    .version(new ItemVersion(newest.itemVersion(), null))
                    .origin(ReviewOrigin.EVENT)
                    .eventId(newest.id())
                    .practiceIds(List.of())
                    .occasions(window.stream().map(ItemEventService.Open::name).collect(Collectors.toSet()))
                    .build());
            settle(workspaceId, window, ItemEventState.STARTED, null);
        } catch (ReviewRequestRejected refusal) {
            if (refusal.eventReason() == ReviewRequestReason.REVIEW_IN_PROGRESS) {
                events.hold(
                        workspaceId,
                        itemId,
                        window,
                        ReviewRequestReason.REVIEW_IN_PROGRESS,
                        clock.instant().plus(events.quietWindow()));
                return;
            }
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
            // A refusal that can pass keeps the whole window waiting, so its occasions still select when it is retried.
            if (refusal.reason().transientReason())
                for (var waiting : window)
                    decide(workspaceId, waiting.id(), waiting.state(), ItemEventState.WAITING, reason);
            else settle(workspaceId, window, ItemEventState.WITHHELD, reason);
        }
    }

    /** Decides the window's newest occasion and coalesces every older one into it. */
    private void settle(
            long workspaceId,
            List<ItemEventService.Open> window,
            ItemEventState newest,
            @Nullable ReviewRequestReason reason) {
        var last = window.getLast();
        for (var waiting : window) {
            if (waiting.id().equals(last.id())) decide(workspaceId, waiting.id(), waiting.state(), newest, reason);
            else
                decide(
                        workspaceId,
                        waiting.id(),
                        waiting.state(),
                        ItemEventState.WITHHELD,
                        ReviewRequestReason.COALESCED);
        }
    }

    @ApplicationModuleListener
    public void on(ItemSourceObserved observed) {
        if (workspaces.findByIdForUpdate(observed.workspaceId()).isEmpty())
            throw new IllegalStateException("The event workspace no longer exists");
        currentness.reconcile(observed.workspaceId(), observed.itemId());
    }

    private void decide(
            long workspaceId,
            UUID eventId,
            ItemEventState from,
            ItemEventState to,
            @Nullable ReviewRequestReason reason) {
        if (!events.decide(workspaceId, eventId, from, to, reason))
            throw new IllegalStateException("The event changed while the workspace was locked");
    }
}
