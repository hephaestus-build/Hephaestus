package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReviewScanStoreServiceTest extends BaseUnitTest {
    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    private final JsonMapper json = new JsonMapper();
    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final ReviewPracticeRepository practices = mock(ReviewPracticeRepository.class);
    private final ReviewItemRepository items = mock(ReviewItemRepository.class);
    private final ReuseCandidateQuery candidates = mock(ReuseCandidateQuery.class);
    private final Review review = Review.builder()
            .id(UUID.randomUUID())
            .workspaceId(1)
            .kind(ReviewKind.SCAN)
            .status(ReviewStatus.RUNNING)
            .inventoryReceipt(json.createObjectNode().put("complete", true))
            .build();

    private ReviewScanStoreService service() {
        return new ReviewScanStoreService(
                reviews,
                practices,
                items,
                candidates,
                mock(ReviewAssignmentQuery.class),
                mock(ResultRepository.class),
                mock(ItemIndexService.class),
                mock(ProductItemRepository.class),
                json,
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(ReviewSnapshotQuery.class),
                mock(ReviewRuntimeQuery.class));
    }

    private ReviewItem member(long id) {
        return ReviewItem.builder()
                .workspaceId(1)
                .reviewId(review.getId())
                .practiceId(2)
                .itemId(id)
                .itemVersion("file:v1")
                .build();
    }

    @Test
    void shouldAttemptAMissOnlyOnce() {
        var member = member(3);
        var id = new ReviewAssignmentId(review.getId(), 2, 3);
        when(reviews.lockByIdAndWorkspaceId(review.getId(), 1)).thenReturn(Optional.of(review));
        when(items.lockAssignment(1, review.getId(), 2, 3)).thenReturn(Optional.of(member));
        when(candidates.find(1, id)).thenReturn(Optional.empty());

        var service = service();
        assertThat(service.reuse(1, id)).isEmpty();
        assertThat(service.reuse(1, id)).isEmpty();

        assertThat(member.getState()).isEqualTo(ReviewItemState.LISTED);
        var detail = member.getDetail();
        assertThat(detail).isNotNull();
        assertThat(detail.path("reuseAttempted").asBoolean()).isTrue();
        verify(candidates, times(1)).find(1, id);
    }

    @Test
    void shouldPreserveReusedMembershipAfterAnotherAttempt() {
        var member = member(4);
        var id = new ReviewAssignmentId(review.getId(), 2, 4);
        var resultId = UUID.randomUUID();
        when(reviews.lockByIdAndWorkspaceId(review.getId(), 1)).thenReturn(Optional.of(review));
        when(items.lockAssignment(1, review.getId(), 2, 4)).thenReturn(Optional.of(member));
        when(candidates.find(1, id))
                .thenReturn(Optional.of(new ReuseCandidateQuery.ReusableResult(
                        Result.builder().id(resultId).build(),
                        new HistoryCustody(true, false, List.of(), List.of(), List.of()))));

        var service = service();
        assertThat(service.reuse(1, id)).contains(resultId);
        assertThat(service.reuse(1, id)).contains(resultId);

        assertThat(member.getState()).isEqualTo(ReviewItemState.REUSED);
        assertThat(member.getResultId()).isEqualTo(resultId);
        verify(candidates, times(1)).find(1, id);
    }
}
