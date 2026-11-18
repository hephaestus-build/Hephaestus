package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.framework.ItemSourceRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FeedbackTargetResolverTest extends BaseUnitTest {
    private final FeedbackBasisRepository basis = mock(FeedbackBasisRepository.class);
    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final ItemSource source = mock(ItemSource.class);
    private final FeedbackTargetResolver resolver =
            new FeedbackTargetResolver(basis, reviews, new ItemSourceRegistry(List.of(source)));
    private final UUID reviewId = UUID.randomUUID();
    private final Feedback feedback = Feedback.builder()
            .id(UUID.randomUUID())
            .workspaceId(1)
            .developerId(5)
            .itemId(2L)
            .reviewId(reviewId)
            .body("Add a focused test.")
            .build();

    @BeforeEach
    void setUp() {
        var row = mock(FeedbackBasisRepository.ItemRow.class);
        when(row.getKind()).thenReturn("scm.pull_request");
        when(row.getSourceId()).thenReturn(3L);
        when(row.getParentId()).thenReturn(4L);
        when(basis.findItems(1L, List.of(2L))).thenReturn(List.of(row));
        var review =
                Review.builder().id(reviewId).workspaceId(1L).itemVersion("v1").build();
        when(reviews.findByIdAndWorkspaceId(reviewId, 1L)).thenReturn(Optional.of(review));
        when(source.supports(any())).thenReturn(true);
    }

    @ParameterizedTest
    @CsvSource({
        "ITEM_REMOVED,ITEM_GONE",
        "ITEM_NOT_VISIBLE,ITEM_NOT_VISIBLE",
        "CONSENT_WITHHELD,CONSENT_WITHHELD",
        "VERSION_UNAVAILABLE,REVIEWED_VERSION_CHANGED"
    })
    void aNewPostStopsWithTheReasonTheSourceGave(ItemCaptureCode code, FeedbackStopReason reason) {
        when(source.pin(any(), any())).thenThrow(new ItemCaptureException(code, "Unavailable"));
        assertReason(() -> resolver.resolve(feedback), reason);
    }

    @ParameterizedTest
    @CsvSource({"ITEM_REMOVED,ITEM_GONE", "ITEM_NOT_VISIBLE,ITEM_NOT_VISIBLE", "CONSENT_WITHHELD,CONSENT_WITHHELD"})
    void anEditOrRetractionStopsWithTheReasonTheSourceGaveNotAsGoneWork(
            ItemCaptureCode code, FeedbackStopReason reason) {
        when(source.pin(any(), any())).thenThrow(new ItemCaptureException(code, "Unavailable"));
        assertReason(() -> resolver.resolveExisting(feedback), reason);
    }

    private static void assertReason(Runnable resolve, FeedbackStopReason reason) {
        assertThatThrownBy(resolve::run)
                .isInstanceOfSatisfying(
                        FeedbackTargetResolver.TargetUnavailable.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(reason));
    }
}
