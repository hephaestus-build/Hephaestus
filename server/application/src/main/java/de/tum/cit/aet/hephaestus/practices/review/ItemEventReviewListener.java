package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventOffered;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventRecorded;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemSourceObserved;
import de.tum.cit.aet.hephaestus.integration.core.api.OccasionSettlement;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ItemEventReviewListener {
    private static final Logger log = LoggerFactory.getLogger(ItemEventReviewListener.class);

    private final ItemEventService events;
    private final ProductItemQuery items;
    private final WorkspaceQueries workspaces;
    private final ReviewService reviews;
    private final ReviewRepository reviewRows;
    private final ResultCurrentness currentness;
    private final PracticeItemOptions itemOptions;
    private final Clock clock;
    private final ReviewRequestTransactions transactions;

    /**
     * Each step that can fetch from the provider runs with no transaction: the stale results' source reads, and the
     * review request. Every decision about the occasions is made under the workspace lock, and a failure leaves the
     * event's publication incomplete, so it is sent again. A capture failure that ends the occasion is not such a
     * failure: {@link OccasionSettlement} records it as the occasion's stop.
     */
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(ItemEventRecorded recorded) {
        review(recorded);
    }

    /** A failed wake leaves the occasion for its next ledger retry. */
    @Async
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @TransactionalEventListener
    public void onOffered(ItemEventOffered offered) {
        try {
            review(new ItemEventRecorded(offered.workspaceId(), offered.eventId(), offered.itemId()));
        } catch (RuntimeException failure) {
            log.atWarn()
                    .addKeyValue("event.name", "practice_review.occasion_offer_failed")
                    .addKeyValue("error.type", failure.getClass().getName())
                    .log("The occasion offer failed and will be tried again from the ledger");
        }
    }

    private void review(ItemEventRecorded recorded) {
        long workspaceId = recorded.workspaceId();
        long itemId = recorded.itemId();
        // A publication sent again after the ledger dropped its occasion has nothing left to decide.
        var found = events.find(workspaceId, recorded.eventId());
        if (found.isEmpty()) return;
        var event = found.orElseThrow();
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
            var outcome = reviewRows
                            .findCommittedEventIds(
                                    recorded.workspaceId(), recorded.itemId(), List.of(recorded.eventId()))
                            .contains(recorded.eventId())
                    ? OccasionSettlement.Outcome.REPLAYED
                    : OccasionSettlement.Outcome.WITHDRAWN;
            if (OccasionSettlement.next(state, OccasionSettlement.Role.NEWEST, outcome, null)
                            .orElseThrow()
                    instanceof OccasionSettlement.Next.Decide decision)
                decide(recorded.workspaceId(), recorded.eventId(), state, decision.state(), decision.reason());
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
            apply(
                    recorded.workspaceId(),
                    recorded.itemId(),
                    window,
                    window.getLast().id(),
                    new Settlement(OccasionSettlement.Outcome.QUIET, null),
                    closes);
            return List.of();
        }
        return window;
    }

    /**
     * The readiness edge of a window: one review at its newest occasion runs what every occasion of the window selects
     * and is ready, and the older occasions are coalesced into it. The review's own transaction decides them
     * ({@link OccasionSettlement.Outcome#RECORDED}). A practice that an earlier review of the item still measures holds
     * the window until that review ends.
     */
    private void edge(long workspaceId, long itemId, List<ItemEventService.Open> window) {
        var newest = window.getLast();
        RuntimeException refusal = null;
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
                    .coalesced(window.stream()
                            .map(ItemEventService.Open::id)
                            .filter(id -> !id.equals(newest.id()))
                            .collect(Collectors.toSet()))
                    .build());
        } catch (ReviewRequestRejected | ItemCaptureException stopped) {
            refusal = stopped;
        }
        // A recorded or replayed review has decided its own window. An older occasion that it did not coalesce stays
        // open, and the ledger offers it again.
        if (refusal == null) return;
        var settlement = settlement(refusal);
        transactions.run(() -> {
            lockWorkspace(workspaceId);
            var ids = window.stream().map(ItemEventService.Open::id).collect(Collectors.toSet());
            // Another offer of the item may have decided some of these occasions since the window was read.
            var open = events.open(workspaceId, itemId).stream()
                    .filter(waiting -> ids.contains(waiting.id()))
                    .toList();
            // The refusal was for the window's newest occasion. Once another offer decided it, it decides nothing.
            if (open.stream().noneMatch(waiting -> waiting.id().equals(newest.id()))) return;
            apply(
                    workspaceId,
                    itemId,
                    open,
                    newest.id(),
                    settlement,
                    clock.instant().plus(events.quietWindow()));
        });
    }

    private record Settlement(
            OccasionSettlement.Outcome outcome, @Nullable ReviewRequestReason reason) {}

    /** The outcome of a refused request. A fault fails the publication, which is sent again. */
    private static Settlement settlement(RuntimeException stop) {
        if (stop instanceof ItemCaptureException capture)
            return switch (capture.code()) {
                case ITEM_REMOVED ->
                    new Settlement(OccasionSettlement.Outcome.ITEM_UNAVAILABLE, ReviewRequestReason.ITEM_GONE);
                case ITEM_NOT_VISIBLE ->
                    new Settlement(OccasionSettlement.Outcome.ITEM_UNAVAILABLE, ReviewRequestReason.ITEM_NOT_VISIBLE);
                // An unavailable pin says nothing about whether the work changed: a replaced connection reaches it too.
                case VERSION_UNAVAILABLE, OCCASION_EVIDENCE_UNAVAILABLE ->
                    new Settlement(OccasionSettlement.Outcome.OCCASION_UNAVAILABLE, null);
                case SOURCE_NOT_READY, CONSENT_WITHHELD, CAPTURE_FAILED, CHECKOUT_UNVERIFIED -> throw capture;
            };
        var refusal = (ReviewRequestRejected) stop;
        if (refusal.eventReason() == ReviewRequestReason.REVIEW_IN_PROGRESS)
            return new Settlement(OccasionSettlement.Outcome.HOLD, null);
        // The pin wraps the newest occasion's missing record as an ineligible request: the older ones keep theirs.
        if (refusal.eventReason() == ReviewRequestReason.OCCASION_EVIDENCE_UNAVAILABLE)
            return new Settlement(OccasionSettlement.Outcome.OCCASION_UNAVAILABLE, null);
        ReviewRequestReason reason = switch (refusal.reason()) {
            case WORKSPACE_INACTIVE -> ReviewRequestReason.WORKSPACE_INACTIVE;
            case BUDGET_UNAVAILABLE -> ReviewRequestReason.BUDGET_EXHAUSTED;
            case MODEL_UNAVAILABLE -> ReviewRequestReason.MODEL_UNAVAILABLE;
            case SOURCE_UNAVAILABLE ->
                throw new IllegalStateException("Only a scan can lack a default branch", refusal);
            case ITEM_INELIGIBLE ->
                Objects.requireNonNullElse(refusal.eventReason(), ReviewRequestReason.NO_ACTIVE_PRACTICE);
            case REQUEST_CONFLICT -> ReviewRequestReason.VERSION_SUPERSEDED;
            // Nothing is decided: the event's publication fails, and the event is offered again.
            case PRACTICES_CHANGED -> throw refusal;
            case SCAN_ACTIVE, REF_UNSUPPORTED, VERSION_CHANGED ->
                throw new IllegalStateException("An item review cannot compete with a scan", refusal);
            case TRIAL_ACTIVE -> throw new IllegalStateException("Only a trial counts the active trials", refusal);
            case SETUP_UNAVAILABLE, SETUP_UNSUPPORTED ->
                throw new IllegalStateException("An event review has no review setup", refusal);
        };
        // A refusal that can pass keeps the whole window waiting, so its occasions still select when it is retried. A
        // conflict concerns the newest occasion's version only.
        var outcome = refusal.reason().transientReason()
                ? OccasionSettlement.Outcome.WAIT
                : refusal.reason() == ReviewRequestRejected.Reason.REQUEST_CONFLICT
                        ? OccasionSettlement.Outcome.REFUSED_NEWEST
                        : OccasionSettlement.Outcome.REFUSED;
        return new Settlement(outcome, reason);
    }

    /** Applies {@link OccasionSettlement#next} to each open occasion of the window, under the workspace lock. */
    private void apply(
            long workspaceId,
            long itemId,
            List<ItemEventService.Open> open,
            UUID newest,
            Settlement settlement,
            Instant holdUntil) {
        List<ItemEventService.Open> held = new ArrayList<>();
        ReviewRequestReason holdReason = null;
        var committed = settlement.outcome() == OccasionSettlement.Outcome.ITEM_UNAVAILABLE
                ? reviewRows.findCommittedEventIds(
                        workspaceId,
                        itemId,
                        open.stream().map(ItemEventService.Open::id).toList())
                : Set.<UUID>of();
        for (var waiting : open) {
            // A committed review proves its own occasion even when that occasion is older in this window.
            boolean replay = committed.contains(waiting.id());
            var role = replay || waiting.id().equals(newest)
                    ? OccasionSettlement.Role.NEWEST
                    : OccasionSettlement.Role.OLDER;
            var outcome = replay ? new Settlement(OccasionSettlement.Outcome.REPLAYED, null) : settlement;
            var next = OccasionSettlement.next(waiting.state(), role, outcome.outcome(), outcome.reason());
            if (next.isEmpty()) continue;
            switch (next.orElseThrow()) {
                case OccasionSettlement.Next.Decide decide ->
                    decide(workspaceId, waiting.id(), waiting.state(), decide.state(), decide.reason());
                case OccasionSettlement.Next.Hold hold -> {
                    held.add(waiting);
                    holdReason = hold.reason();
                }
            }
        }
        if (!held.isEmpty()) events.hold(workspaceId, itemId, held, Objects.requireNonNull(holdReason), holdUntil);
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
