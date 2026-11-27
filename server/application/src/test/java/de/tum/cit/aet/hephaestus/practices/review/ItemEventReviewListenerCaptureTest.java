package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventRecorded;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventView;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.OccasionKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.practices.PracticeItemOptions;
import de.tum.cit.aet.hephaestus.practices.result.ResultCurrentness;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A capture failure that can never pass ends the occasion withheld for its reason and completes the event's
 * publication. A capture fault fails the publication, which is sent again, as before.
 */
@Tag("unit")
class ItemEventReviewListenerCaptureTest {

    private static final long WORKSPACE = 7L;
    private static final long ITEM = 11L;
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final ItemEventName PUSHED = new ItemEventName("scm.pull_request.synchronized");
    private static final ItemKind KIND = new ItemKind("scm.pull_request");

    private final ItemEventService events = mock(ItemEventService.class);
    private final ProductItemQuery items = mock(ProductItemQuery.class);
    private final WorkspaceQueries workspaces = mock(WorkspaceQueries.class);
    private final ReviewService reviews = mock(ReviewService.class);
    private final PracticeItemOptions itemOptions = mock(PracticeItemOptions.class);
    private final ReviewRequestTransactions transactions = mock(ReviewRequestTransactions.class);
    private final UUID occasion = UUID.randomUUID();

    private final ItemEventReviewListener listener = new ItemEventReviewListener(
            events,
            items,
            workspaces,
            reviews,
            mock(ReviewRepository.class),
            mock(ResultCurrentness.class),
            itemOptions,
            Clock.fixed(NOW, ZoneOffset.UTC),
            transactions);

    @ParameterizedTest
    @CsvSource({
        "VERSION_UNAVAILABLE, OCCASION_EVIDENCE_UNAVAILABLE",
        "ITEM_REMOVED, ITEM_GONE",
        "ITEM_NOT_VISIBLE, ITEM_NOT_VISIBLE",
        "OCCASION_EVIDENCE_UNAVAILABLE, OCCASION_EVIDENCE_UNAVAILABLE",
    })
    void shouldRecordTheStopOfAnOccasionThatCanNeverBePinnedWithoutFailingThePublication(
            ItemCaptureCode code, ReviewRequestReason reason) {
        offer(code);

        assertThatCode(() -> listener.on(new ItemEventRecorded(WORKSPACE, occasion, ITEM)))
                .doesNotThrowAnyException();

        verify(events).decide(WORKSPACE, occasion, ItemEventState.RECORDED, ItemEventState.WITHHELD, reason);
    }

    @ParameterizedTest
    @EnumSource(
            value = ItemCaptureCode.class,
            names = {"SOURCE_NOT_READY", "CONSENT_WITHHELD", "CAPTURE_FAILED", "CHECKOUT_UNVERIFIED"})
    void shouldFailThePublicationOfACaptureFaultAndDecideNothing(ItemCaptureCode code) {
        offer(code);

        assertThatThrownBy(() -> listener.on(new ItemEventRecorded(WORKSPACE, occasion, ITEM)))
                .isInstanceOf(ItemCaptureException.class);

        verify(events, never()).decide(anyLong(), any(), any(), any(), any());
    }

    @Test
    void shouldDecideNothingWhenAnotherOfferDecidedTheNewestOccasionBeforeTheRefusalSettles() {
        offer(ItemCaptureCode.ITEM_NOT_VISIBLE);
        // The other offer's review decided the occasion after this window was read.
        var open = new ItemEventService.Open(occasion, PUSHED, "head", ItemEventState.RECORDED, null, NOW, NOW);
        when(events.open(WORKSPACE, ITEM)).thenReturn(List.of(open)).thenReturn(List.of());

        assertThatCode(() -> listener.on(new ItemEventRecorded(WORKSPACE, occasion, ITEM)))
                .doesNotThrowAnyException();

        verify(events, never()).decide(anyLong(), any(), any(), any(), any());
    }

    @Test
    void shouldWithholdAWithdrawnItemsOccasionAsNotVisibleNeverAsGone() {
        offer(ItemCaptureCode.ITEM_NOT_VISIBLE);
        // The workspace withdrew the repository: its pull request left the index, and it can still exist.
        when(items.find(WORKSPACE, ITEM)).thenReturn(Optional.empty());

        assertThatCode(() -> listener.on(new ItemEventRecorded(WORKSPACE, occasion, ITEM)))
                .doesNotThrowAnyException();

        verify(events)
                .decide(
                        WORKSPACE,
                        occasion,
                        ItemEventState.RECORDED,
                        ItemEventState.WITHHELD,
                        ReviewRequestReason.ITEM_NOT_VISIBLE);
        verify(reviews, never()).request(any());
    }

    @Test
    void shouldLeaveAnOccasionThatItsReviewAlreadyStartedWhenItsItemLeavesTheIndex() {
        offer(ItemCaptureCode.ITEM_NOT_VISIBLE);
        when(items.find(WORKSPACE, ITEM)).thenReturn(Optional.empty());
        when(events.find(WORKSPACE, occasion))
                .thenReturn(Optional.of(new ItemEventView(PUSHED, "head", ItemEventState.STARTED, NOW)));

        assertThatCode(() -> listener.on(new ItemEventRecorded(WORKSPACE, occasion, ITEM)))
                .doesNotThrowAnyException();

        verify(events, never()).decide(anyLong(), any(), any(), any(), any());
    }

    @Test
    void shouldWithholdOnlyTheOccasionWhoseCaptureStoppedAndKeepTheOlderOneEligible() {
        var older = twoOccasionWindow(ItemCaptureCode.OCCASION_EVIDENCE_UNAVAILABLE);

        assertThatCode(() -> listener.on(new ItemEventRecorded(WORKSPACE, occasion, ITEM)))
                .doesNotThrowAnyException();

        verify(events)
                .decide(
                        WORKSPACE,
                        occasion,
                        ItemEventState.RECORDED,
                        ItemEventState.WITHHELD,
                        ReviewRequestReason.OCCASION_EVIDENCE_UNAVAILABLE);
        verify(events, never()).decide(eq(WORKSPACE), eq(older), any(), any(), any());
    }

    @Test
    void shouldLeaveTheWindowToTheReviewWhenTheRequestRecordsOrReplaysIt() {
        var older = twoOccasionWindow(ItemCaptureCode.OCCASION_EVIDENCE_UNAVAILABLE);
        // Recorded or replayed: the review's own transaction decided its window.
        doReturn(UUID.randomUUID()).when(reviews).request(any());

        assertThatCode(() -> listener.on(new ItemEventRecorded(WORKSPACE, occasion, ITEM)))
                .doesNotThrowAnyException();

        verify(events, never()).decide(anyLong(), any(), any(), any(), any());
        verify(reviews)
                .request(argThat(
                        request -> request.coalesced().equals(Set.of(older)) && occasion.equals(request.eventId())));
    }

    @Test
    void shouldKeepTheOlderOccasionEligibleWhenThePinRefusesTheNewestOnesMissingRecord() {
        var older = twoOccasionWindow(ItemCaptureCode.OCCASION_EVIDENCE_UNAVAILABLE);
        // The real pin wraps the newest occasion's missing record as an ineligible request.
        doThrow(new ReviewRequestRejected(
                        ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                        "The occasion kept no record of the work as it was.",
                        ReviewRequestReason.OCCASION_EVIDENCE_UNAVAILABLE,
                        new ItemCaptureException(ItemCaptureCode.OCCASION_EVIDENCE_UNAVAILABLE, "expired")))
                .when(reviews)
                .request(any());

        assertThatCode(() -> listener.on(new ItemEventRecorded(WORKSPACE, occasion, ITEM)))
                .doesNotThrowAnyException();

        verify(events)
                .decide(
                        WORKSPACE,
                        occasion,
                        ItemEventState.RECORDED,
                        ItemEventState.WITHHELD,
                        ReviewRequestReason.OCCASION_EVIDENCE_UNAVAILABLE);
        verify(events, never()).decide(eq(WORKSPACE), eq(older), any(), any(), any());
    }

    @Test
    void shouldWithholdEveryOccasionOfARefusedWindowForItsOwnReasonNeverAsCoalesced() {
        var older = twoOccasionWindow(ItemCaptureCode.OCCASION_EVIDENCE_UNAVAILABLE);
        doThrow(new ReviewRequestRejected(
                        ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                        "No active practice reviews on this event.",
                        ReviewRequestReason.NO_ACTIVE_PRACTICE,
                        null))
                .when(reviews)
                .request(any());

        assertThatCode(() -> listener.on(new ItemEventRecorded(WORKSPACE, occasion, ITEM)))
                .doesNotThrowAnyException();

        for (var id : List.of(older, occasion))
            verify(events)
                    .decide(
                            WORKSPACE,
                            id,
                            ItemEventState.RECORDED,
                            ItemEventState.WITHHELD,
                            ReviewRequestReason.NO_ACTIVE_PRACTICE);
        verify(events, never()).decide(anyLong(), any(), any(), any(), eq(ReviewRequestReason.COALESCED));
    }

    @Test
    void shouldWithholdOnlyTheNewestOccasionWhenItsVersionConflicts() {
        var older = twoOccasionWindow(ItemCaptureCode.OCCASION_EVIDENCE_UNAVAILABLE);
        doThrow(new ReviewRequestRejected(
                        ReviewRequestRejected.Reason.REQUEST_CONFLICT, "The event version does not match the review."))
                .when(reviews)
                .request(any());

        assertThatCode(() -> listener.on(new ItemEventRecorded(WORKSPACE, occasion, ITEM)))
                .doesNotThrowAnyException();

        verify(events)
                .decide(
                        WORKSPACE,
                        occasion,
                        ItemEventState.RECORDED,
                        ItemEventState.WITHHELD,
                        ReviewRequestReason.VERSION_SUPERSEDED);
        verify(events, never()).decide(eq(WORKSPACE), eq(older), any(), any(), any());
    }

    /** A two-occasion window, the recorded occasion newest; the request fails to pin with {@code code}. */
    private UUID twoOccasionWindow(ItemCaptureCode code) {
        offer(code);
        var older = UUID.randomUUID();
        when(itemOptions.occasion(KIND, PUSHED)).thenReturn(OccasionKind.MILESTONE);
        when(events.open(WORKSPACE, ITEM))
                .thenReturn(List.of(
                        new ItemEventService.Open(
                                older, PUSHED, "head", ItemEventState.RECORDED, null, NOW.minusSeconds(60), NOW),
                        new ItemEventService.Open(occasion, PUSHED, "head", ItemEventState.RECORDED, null, NOW, NOW)));
        when(events.windowCloses(any())).thenReturn(NOW.minusSeconds(1));
        return older;
    }

    @Test
    void shouldCompleteAPublicationWhoseOccasionTheLedgerNoLongerHolds() {
        when(events.find(WORKSPACE, occasion)).thenReturn(Optional.empty());

        assertThatCode(() -> listener.on(new ItemEventRecorded(WORKSPACE, occasion, ITEM)))
                .doesNotThrowAnyException();

        verify(reviews, never()).request(any());
    }

    /** One recorded occasion at its readiness edge, whose review request fails to pin with {@code code}. */
    @SuppressWarnings("unchecked")
    private void offer(ItemCaptureCode code) {
        when(events.find(WORKSPACE, occasion))
                .thenReturn(Optional.of(new ItemEventView(PUSHED, "head", ItemEventState.RECORDED, NOW)));
        when(transactions.write(any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(0)).get());
        doAnswer(call -> {
                    ((Runnable) call.getArgument(0)).run();
                    return null;
                })
                .when(transactions)
                .run(any());
        when(workspaces.findByIdForUpdate(WORKSPACE))
                .thenReturn(Optional.of(mock(WorkspaceQueries.ReviewWorkspace.class)));
        when(items.find(WORKSPACE, ITEM)).thenReturn(Optional.of(new ItemRef(WORKSPACE, ITEM, KIND, 1L, null, null)));
        when(itemOptions.occasion(KIND, PUSHED)).thenReturn(OccasionKind.IMMEDIATE);
        when(events.open(WORKSPACE, ITEM))
                .thenReturn(List.of(
                        new ItemEventService.Open(occasion, PUSHED, "head", ItemEventState.RECORDED, null, NOW, NOW)));
        when(events.decide(anyLong(), any(), any(), any(), any())).thenReturn(true);
        when(reviews.request(any())).thenThrow(new ItemCaptureException(code, "unavailable"));
    }
}
