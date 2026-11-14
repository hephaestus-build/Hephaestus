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
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ItemEventReviewListener {
    private final ItemEventService events;
    private final ProductItemQuery items;
    private final WorkspaceQueries workspaces;
    private final ReviewService reviews;
    private final ResultCurrentness currentness;
    private final PracticeItemOptions itemOptions;
    private final Clock clock;
    private final ReviewRequestTransactions transactions;

    /**
     * Each step that can fetch from the provider runs with no transaction: the stale results' source reads, and the
     * review request. Every decision about the occasions is made under the workspace lock, and a failure leaves the
     * event's publication incomplete, so it is sent again.
     */
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(ItemEventRecorded recorded) {
        long workspaceId = recorded.workspaceId();
        long itemId = recorded.itemId();
        var event = events.find(workspaceId, recorded.eventId()).orElseThrow();
        var stale = event.name().value().startsWith("scm.") ? currentness.stale(workspaceId, itemId) : Set.<UUID>of();
        var window = transactions.write(() -> {
            lockWorkspace(workspaceId);
            currentness.end(workspaceId, itemId, stale);
            return window(recorded);
        });
        if (!window.isEmpty()) edge(workspaceId, itemId, window);
    }

    /**
     * The occasions that reach their readiness edge now, decided under the workspace lock. An occasion that waits, or
     * one with nothing to review, is decided here and gives none.
     */
    private List<ItemEventService.Open> window(ItemEventRecorded recorded) {
        var event = events.find(recorded.workspaceId(), recorded.eventId()).orElseThrow();
        var state = event.state();
        if (state.isTerminal()) return List.of();
        var item = items.find(recorded.workspaceId(), recorded.itemId());
        if (item.isEmpty()) {
            decide(
                    recorded.workspaceId(),
                    recorded.eventId(),
                    state,
                    ItemEventState.WITHHELD,
                    ReviewRequestReason.ITEM_GONE);
            return List.of();
        }
        // The source pins the version later and offers the occasion again; the expiry bounds the wait.
        if (event.itemVersion() == null) return List.of();
        var kind = item.orElseThrow().kind();
        var open = events.open(recorded.workspaceId(), recorded.itemId());
        if (!itemOptions.occasion(kind, event.name()).joinsWindow())
            return open.stream()
                    .filter(waiting -> waiting.id().equals(recorded.eventId()))
                    .toList();
        var window = open.stream()
                .filter(waiting -> itemOptions.occasion(kind, waiting.name()).joinsWindow())
                .toList();
        if (window.isEmpty()) return List.of();
        var closes = events.windowCloses(window);
        if (closes.isAfter(clock.instant())) {
            events.hold(recorded.workspaceId(), recorded.itemId(), window, ReviewRequestReason.QUIET_WINDOW, closes);
            return List.of();
        }
        return window;
    }

    /**
     * The readiness edge of a window: one review at its newest occasion runs what every occasion of the window selects
     * and is ready, and the older occasions are coalesced into it. A practice that an earlier review of the item still
     * measures holds the window until that review ends.
     */
    private void edge(long workspaceId, long itemId, List<ItemEventService.Open> window) {
        var newest = window.getLast();
        ReviewRequestRejected refusal = null;
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
        } catch (ReviewRequestRejected rejected) {
            refusal = rejected;
        }
        var outcome = refusal;
        transactions.run(() -> {
            lockWorkspace(workspaceId);
            var ids = window.stream().map(ItemEventService.Open::id).collect(Collectors.toSet());
            // Another edge of the item may have decided some of these occasions since the window was read.
            var open = events.open(workspaceId, itemId).stream()
                    .filter(waiting -> ids.contains(waiting.id()))
                    .toList();
            if (!open.isEmpty()) settle(workspaceId, itemId, newest.id(), open, outcome);
        });
    }

    private void settle(
            long workspaceId,
            long itemId,
            UUID newest,
            List<ItemEventService.Open> open,
            @Nullable ReviewRequestRejected refusal) {
        if (refusal == null) {
            coalesce(workspaceId, newest, open, ItemEventState.STARTED, null);
            return;
        }
        if (refusal.eventReason() == ReviewRequestReason.REVIEW_IN_PROGRESS) {
            events.hold(
                    workspaceId,
                    itemId,
                    open,
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
            for (var waiting : open) decide(workspaceId, waiting.id(), waiting.state(), ItemEventState.WAITING, reason);
        else coalesce(workspaceId, newest, open, ItemEventState.WITHHELD, reason);
    }

    /** Decides the window's newest occasion and coalesces every older one into it. */
    private void coalesce(
            long workspaceId,
            UUID newest,
            List<ItemEventService.Open> open,
            ItemEventState decision,
            @Nullable ReviewRequestReason reason) {
        for (var waiting : open) {
            if (waiting.id().equals(newest)) decide(workspaceId, waiting.id(), waiting.state(), decision, reason);
            else
                decide(
                        workspaceId,
                        waiting.id(),
                        waiting.state(),
                        ItemEventState.WITHHELD,
                        ReviewRequestReason.COALESCED);
        }
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(ItemSourceObserved observed) {
        var stale = currentness.stale(observed.workspaceId(), observed.itemId());
        transactions.run(() -> {
            lockWorkspace(observed.workspaceId());
            currentness.end(observed.workspaceId(), observed.itemId(), stale);
        });
    }

    private void lockWorkspace(long workspaceId) {
        if (workspaces.findByIdForUpdate(workspaceId).isEmpty())
            throw new IllegalStateException("The event workspace no longer exists");
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
