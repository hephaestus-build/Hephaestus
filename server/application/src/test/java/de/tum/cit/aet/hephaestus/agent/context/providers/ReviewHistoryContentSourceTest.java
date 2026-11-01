package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.HistoryCopies;
import de.tum.cit.aet.hephaestus.agent.context.HistoryCopy;
import de.tum.cit.aet.hephaestus.agent.context.PublicFeedbackSnapshot;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.integration.core.framework.RegisteredItemLabels;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLabel;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ReviewHistoryContentSourceTest extends BaseUnitTest {
    @Test
    void shouldAskForTheFrozenAssignmentAndCopyOnlyVouchedHistory() {
        var results = mock(ResultHistoryQuery.class);
        var copies = mock(HistoryCopies.class);
        var assignment = RuntimeReviewTestData.assignment(5L, null);
        var vouched = mock(ResultHistoryEntry.class);
        var unknown = mock(ResultHistoryEntry.class);
        var actual = new HistoryCopy(true, List.of(), List.of(), List.of());
        var incomplete = new HistoryCopy(false, List.of(), List.of(), List.of());
        when(results.forAssignment(1, assignment.id(), 20)).thenReturn(List.of(vouched, unknown));
        when(copies.of(vouched)).thenReturn(actual);
        when(copies.of(unknown)).thenReturn(incomplete);
        when(copies.record(actual)).thenReturn(true);
        var source = new ReviewHistoryContentSource(
                results, mock(FeedbackHistoryQuery.class), copies, mock(RegisteredItemLabels.class), new JsonMapper());
        assertThat(source.previousResults(assignment)).containsExactly(vouched);
        verify(copies).record(actual);
        verify(copies).record(incomplete);
    }

    private final FeedbackHistoryQuery feedback = mock(FeedbackHistoryQuery.class);
    private final RegisteredItemLabels labels = mock(RegisteredItemLabels.class);
    private final JsonMapper mapper = new JsonMapper();
    private final ReviewHistoryContentSource source = new ReviewHistoryContentSource(
            mock(ResultHistoryQuery.class),
            feedback,
            new HistoryCopies(mock(PersonDataCopyRecorder.class)),
            labels,
            mapper);
    private final Instant deliveredAt = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void shouldIncludeOnlyDeliveredPublicFeedbackForTheViewerFromOtherReviews() {
        var job = RuntimeReviewTestData.job();
        var visible = entry(5, FeedbackDestination.ON_WORK, deliveredAt, "Public feedback", UUID.randomUUID(), true);
        when(feedback.forItem(1, 2, 5, 100))
                .thenReturn(List.of(
                        entry(6, FeedbackDestination.ON_WORK, deliveredAt, "Other developer", UUID.randomUUID(), false),
                        entry(
                                5,
                                FeedbackDestination.CONVERSATION,
                                deliveredAt,
                                "Private feedback",
                                UUID.randomUUID(),
                                false),
                        entry(5, FeedbackDestination.ON_WORK, null, "Undelivered feedback", UUID.randomUUID(), false),
                        entry(5, FeedbackDestination.ON_WORK, deliveredAt, null, UUID.randomUUID(), false),
                        entry(5, FeedbackDestination.ON_WORK, deliveredAt, "Current review", job.getReviewId(), false),
                        visible,
                        visible));
        var assignment = RuntimeReviewTestData.assignment(5L, null);
        var read = source.publicSameItemFeedback(
                job, List.of(assignment, assignment, RuntimeReviewTestData.assignment(null, null)));
        assertThat(read.response().history().feedback())
                .containsExactly(mapper.treeToValue(visible.publicHistory(), PublicFeedbackSnapshot.Entry.class));
        assertThat(read.copies())
                .containsExactly(new HistoryCopy(true, List.of(), List.of(20L), visible.sourceJobIds()));
        assertThat(read.response().history().item()).satisfies(item -> {
            assertThat(item.id()).isEqualTo("2");
            assertThat(item.label()).isEqualTo("docs.document");
            assertThat(item.parentId()).isNull();
            assertThat(item.title()).isNull();
        });
        verify(feedback).forItem(1, 2, 5, 100);
    }

    @Test
    void shouldRefusePublicHistoryWhenTheOwnerCannotVouchForItsCopy() {
        when(feedback.forItem(1, 2, 5, 100))
                .thenReturn(List.of(entry(
                        5, FeedbackDestination.ON_WORK, deliveredAt, "Public feedback", UUID.randomUUID(), false)));
        assertThatThrownBy(() -> source.publicSameItemFeedback(
                        RuntimeReviewTestData.job(), List.of(RuntimeReviewTestData.assignment(5L, null))))
                .isInstanceOf(ResultAdmissionService.PublicHistoryRefusedException.class)
                .hasMessage("Historical feedback has no complete copy authority");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldUseTheProviderLabelWithAnOptionalNumber(boolean numbered) {
        var original = RuntimeReviewTestData.assignment(null, null);
        var item = new ItemRef(1, 2, original.item().kind(), null, 7L, "document/body");
        var assignment = withItem(original, item);
        when(labels.labels(1, List.of(item)))
                .thenReturn(Map.of(
                        2L,
                        new ItemLabel(
                                2,
                                "Document",
                                "Design",
                                "Document",
                                numbered ? 42 : null,
                                "team/docs",
                                "https://example.test/docs/2",
                                null)));
        var read = source.publicSameItemFeedback(RuntimeReviewTestData.job(), List.of(assignment));
        assertThat(read.response().history().feedback()).isEmpty();
        assertThat(read.copies()).isEmpty();
        assertThat(read.response().history().item()).satisfies(ref -> {
            assertThat(ref.label()).isEqualTo(numbered ? "Document #42" : "Document");
            assertThat(ref.parentId()).isEqualTo("7");
            assertThat(ref.title()).isEqualTo("Design");
            assertThat(ref.url()).isEqualTo("https://example.test/docs/2");
            assertThat(ref.container()).isEqualTo("team/docs");
            assertThat(ref.path()).isEqualTo("document/body");
        });
    }

    @Test
    void shouldRejectPublicHistoryAcrossDifferentItems() {
        var first = RuntimeReviewTestData.assignment(null, null);
        var second = withItem(first, new ItemRef(1, 3, first.item().kind(), 20L, null, null));
        assertThatThrownBy(() -> source.publicSameItemFeedback(RuntimeReviewTestData.job(), List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Public history requires one item");
    }

    private ReviewAssignmentSnapshot withItem(ReviewAssignmentSnapshot assignment, ItemRef item) {
        return new ReviewAssignmentSnapshot(
                assignment.id(),
                item,
                assignment.version(),
                assignment.developerId(),
                assignment.practiceSlug(),
                assignment.practice());
    }

    private FeedbackHistoryEntry entry(
            long developer,
            FeedbackDestination destination,
            @Nullable Instant delivered,
            @Nullable String body,
            UUID reviewId,
            boolean complete) {
        UUID id = UUID.randomUUID();
        var projection = destination == FeedbackDestination.ON_WORK && delivered != null
                ? mapper.valueToTree(new PublicFeedbackSnapshot.Entry(
                        id,
                        destination,
                        FeedbackState.DELIVERED,
                        delivered,
                        "docs:one",
                        body,
                        body == null,
                        Currentness.CURRENT,
                        false,
                        List.of(),
                        List.of()))
                : null;
        return new FeedbackHistoryEntry(
                id,
                reviewId,
                2L,
                developer,
                destination,
                FeedbackState.DELIVERED,
                body,
                deliveredAt,
                delivered,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                List.of(UUID.randomUUID()),
                List.of(),
                List.of(20L),
                null,
                complete,
                projection);
    }
}
