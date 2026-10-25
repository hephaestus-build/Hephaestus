package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.LongStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * How many reads one retained run's attribution and its reviews' recorded states cost, that a run's selection is all
 * or nothing, and that counts are never partly read. The persisted scope of the queries is proven against PostgreSQL in
 * {@code ReviewAttemptsContentSourceIntegrationTest}.
 */
class ReviewAssignmentQueryServiceRetainedTest extends BaseUnitTest {
    private static final long WORKSPACE = 1;
    private static final long ITEM = 7;
    private static final long DEVELOPER = 5;
    private static final UUID REVIEW = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID JOB = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final ItemRef REF = new ItemRef(WORKSPACE, ITEM, new ItemKind("scm.pull_request"), 70L, null, null);

    private final JsonMapper json = new JsonMapper();
    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final ReviewItemRepository items = mock(ReviewItemRepository.class);
    private final ProductItemQuery itemQuery = mock(ProductItemQuery.class);
    private final ReviewAssignmentQueryService service = new ReviewAssignmentQueryService(
            reviews,
            mock(ReviewPracticeRepository.class),
            items,
            mock(PracticeRepository.class),
            mock(PracticeVersionRepository.class),
            itemQuery,
            json);

    private void itemReview() {
        when(reviews.findByWorkspaceIdAndIdIn(eq(WORKSPACE), anyCollection()))
                .thenReturn(List.of(Review.builder()
                        .id(REVIEW)
                        .workspaceId(WORKSPACE)
                        .itemId(ITEM)
                        .kind(ReviewKind.ITEM)
                        .build()));
        when(itemQuery.findAll(eq(WORKSPACE), anyCollection())).thenReturn(Map.of(ITEM, REF));
    }

    private void keyedRows(List<ReviewItem> first, List<ReviewItem> second) {
        when(items.findByWorkspaceIdAndReviewedItems(
                        eq(WORKSPACE), aryEq(new UUID[] {REVIEW}), aryEq(new Long[] {ITEM})))
                .thenReturn(first)
                .thenReturn(second);
    }

    private ReviewItem row(long practiceId) {
        return row(practiceId, json.createObjectNode().put("developerId", DEVELOPER));
    }

    private static ReviewItem row(long practiceId, @Nullable JsonNode detail) {
        return ReviewItem.builder()
                .workspaceId(WORKSPACE)
                .reviewId(REVIEW)
                .practiceId(practiceId)
                .itemId(ITEM)
                .itemVersion("retained-work-version")
                .jobId(JOB)
                .detail(detail)
                .build();
    }

    private static List<ReviewAssignmentId> keys(long practices) {
        return LongStream.rangeClosed(1, practices)
                .mapToObj(practice -> new ReviewAssignmentId(REVIEW, practice, ITEM))
                .toList();
    }

    @Test
    void shouldReadThirtySevenSameItemAssignmentsInOneScopedQueryAndResolveTheItemOnce() {
        itemReview();
        var rows = new ArrayList<ReviewItem>();
        LongStream.rangeClosed(1, 38).forEach(practice -> rows.add(row(practice)));
        keyedRows(rows, rows);

        var retained = service.retained(WORKSPACE, REVIEW, JOB, keys(37));

        assertThat(retained)
                .as("practice 38 shares the item but was not recorded by this run, so it is not read out")
                .extracting(ReviewAssignmentQuery.Retained::id)
                .containsExactlyElementsOf(keys(37));
        assertThat(retained).allSatisfy(entry -> {
            assertThat(entry.developerId()).isEqualTo(DEVELOPER);
            assertThat(entry.item()).isEqualTo(REF);
        });
        verify(reviews, times(1)).findByWorkspaceIdAndIdIn(eq(WORKSPACE), anyCollection());
        verify(items, times(1)).findByWorkspaceIdAndReviewedItems(eq(WORKSPACE), any(), any());
        verify(items, never())
                .findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(anyLong(), any(), anyLong(), anyLong());
        verify(items, never()).findByWorkspaceIdAndJobIdIn(anyLong(), anyCollection());
        verify(itemQuery, times(1)).findAll(eq(WORKSPACE), anyCollection());
    }

    @Test
    void shouldAttributeNothingWhenOneSelectedRowIsUnreadableOrARecordedKeyHasNoRow() {
        itemReview();
        var rows = new ArrayList<ReviewItem>();
        LongStream.rangeClosed(1, 37).forEach(practice -> rows.add(row(practice)));
        rows.set(20, row(21, json.createObjectNode().put("developerId", "5")));
        keyedRows(rows, rows.subList(0, 36));

        assertThat(service.retained(WORKSPACE, REVIEW, JOB, keys(37)))
                .as("a developer that is not a number makes the whole run unattributed, not a partial answer")
                .isEmpty();
        assertThat(service.retained(WORKSPACE, REVIEW, JOB, keys(37)))
                .as("a recorded key without its row makes the whole run unattributed")
                .isEmpty();
    }

    @Test
    void shouldReadNothingForAKeyOfAnotherReview() {
        var foreign = new ArrayList<>(keys(2));
        foreign.add(new ReviewAssignmentId(UUID.randomUUID(), 3, ITEM));

        assertThat(service.retained(WORKSPACE, REVIEW, JOB, foreign)).isEmpty();
        verify(reviews, never()).findByWorkspaceIdAndIdIn(anyLong(), anyCollection());
        verify(items, never()).findByWorkspaceIdAndReviewedItems(anyLong(), any(), any());
    }

    @Test
    void shouldAttributeABoundRunWithoutRecordedKeysOnlyWhenEveryBoundRowIsReadable() {
        itemReview();
        var unreadable = row(2, null);
        when(items.findByWorkspaceIdAndJobIdIn(WORKSPACE, List.of(JOB)))
                .thenReturn(List.of(row(1)))
                .thenReturn(List.of(row(1), unreadable));

        assertThat(service.retained(WORKSPACE, REVIEW, JOB, List.of()))
                .extracting(ReviewAssignmentQuery.Retained::developerId)
                .containsExactly(DEVELOPER);
        assertThat(service.retained(WORKSPACE, REVIEW, JOB, List.of()))
                .as("one bound row without attribution makes the run unattributed")
                .isEmpty();
        verify(items, never()).findByWorkspaceIdAndReviewedItems(anyLong(), any(), any());
    }

    @Test
    void shouldAttributeEachRunOfOneBatchFromItsOwnRowsOnly() {
        itemReview();
        var boundJob = UUID.randomUUID();
        var shared = row(3);
        shared.setJobId(boundJob);
        when(items.findByWorkspaceIdAndReviewedItems(
                        eq(WORKSPACE), aryEq(new UUID[] {REVIEW}), aryEq(new Long[] {ITEM})))
                .thenReturn(List.of(row(1), row(2), shared));
        when(items.findByWorkspaceIdAndJobIdIn(eq(WORKSPACE), anyCollection())).thenReturn(List.of(shared));
        var keyed = List.of(new ReviewAssignmentId(REVIEW, 1, ITEM), new ReviewAssignmentId(REVIEW, 3, ITEM));
        var missingReview = UUID.randomUUID();

        var retained = service.retained(
                WORKSPACE,
                List.of(
                        new ReviewAssignmentQuery.RetainedRun(REVIEW, JOB, keyed),
                        new ReviewAssignmentQuery.RetainedRun(REVIEW, boundJob, List.of()),
                        new ReviewAssignmentQuery.RetainedRun(missingReview, UUID.randomUUID(), List.of())));

        assertThat(retained).as("a run whose review is gone is not answered").containsOnlyKeys(JOB, boundJob);
        assertThat(retained.get(JOB))
                .as("the keyed run reads exactly its recorded keys, the shared row once")
                .extracting(ReviewAssignmentQuery.Retained::id)
                .containsExactlyElementsOf(keyed);
        assertThat(retained.get(boundJob))
                .as("the bound run reads only the row bound to it, not the keyed run's rows")
                .extracting(ReviewAssignmentQuery.Retained::id)
                .containsExactly(new ReviewAssignmentId(REVIEW, 3, ITEM));
    }

    private static Review partialReview() {
        return Review.builder()
                .id(REVIEW)
                .workspaceId(WORKSPACE)
                .itemId(ITEM)
                .kind(ReviewKind.ITEM)
                .status(ReviewStatus.PARTIAL)
                .stopReason(ReviewStopReason.EXECUTION_INCOMPLETE)
                .build();
    }

    private static ReviewItem recorded(ReviewItem row, ReviewItemState state) {
        row.setState(state);
        return row;
    }

    @Test
    void shouldCountOnlyTheDevelopersRowsOfAPairAskedTwiceInOneReadOfEach() {
        var pair = new ReviewAssignmentQuery.ReviewedItem(REVIEW, ITEM);
        when(reviews.findByWorkspaceIdAndIdIn(WORKSPACE, Set.of(REVIEW))).thenReturn(List.of(partialReview()));
        when(items.findByWorkspaceIdAndReviewedItems(WORKSPACE, new UUID[] {REVIEW}, new Long[] {ITEM}))
                .thenReturn(List.of(
                        recorded(row(1), ReviewItemState.JUDGED),
                        recorded(row(2), ReviewItemState.WITHHELD),
                        recorded(row(3, json.createObjectNode().putNull("developerId")), ReviewItemState.WITHHELD),
                        recorded(
                                row(4, json.createObjectNode().put("developerId", DEVELOPER + 1)),
                                ReviewItemState.WITHHELD)));

        assertThat(service.recorded(WORKSPACE, DEVELOPER, List.of(pair, pair)))
                .as("a row naming nobody or a colleague is not the developer's")
                .containsOnly(entry(
                        pair,
                        new ReviewAssignmentQuery.Recorded(
                                ReviewStatus.PARTIAL,
                                ReviewStopReason.EXECUTION_INCOMPLETE,
                                new ReviewAssignmentQuery.AssignmentCounts(2, 1, 0, 1, 0))));
        verify(reviews, times(1)).findByWorkspaceIdAndIdIn(anyLong(), any());
        verify(items, times(1)).findByWorkspaceIdAndReviewedItems(anyLong(), any(), any());
    }

    @Test
    void shouldKeepHowTheReviewEndedButCountNothingWhenARowCannotSayWhoseWorkItWas() {
        var pair = new ReviewAssignmentQuery.ReviewedItem(REVIEW, ITEM);
        var otherItem = new ReviewAssignmentQuery.ReviewedItem(REVIEW, ITEM + 1);
        var otherWorkspace = new ReviewAssignmentQuery.ReviewedItem(UUID.randomUUID(), ITEM);
        when(reviews.findByWorkspaceIdAndIdIn(eq(WORKSPACE), any())).thenReturn(List.of(partialReview()));
        when(items.findByWorkspaceIdAndReviewedItems(eq(WORKSPACE), any(), any()))
                .thenReturn(List.of(
                        recorded(row(1), ReviewItemState.JUDGED),
                        recorded(row(2, json.createObjectNode()), ReviewItemState.WITHHELD)));

        assertThat(service.recorded(WORKSPACE, DEVELOPER, List.of(pair, otherItem, otherWorkspace)))
                .as("the review's end is known while its counts are not; no state is given for an item the review"
                        + " has no row for, or for a review outside the workspace")
                .containsOnly(entry(
                        pair,
                        new ReviewAssignmentQuery.Recorded(
                                ReviewStatus.PARTIAL, ReviewStopReason.EXECUTION_INCOMPLETE, null)));
    }
}
