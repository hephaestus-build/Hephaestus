package de.tum.cit.aet.hephaestus.practices.trace;

import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.facts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.FoundBy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.PullRequestItemDescriptor;
import de.tum.cit.aet.hephaestus.practices.PracticeItemOptions;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultPresentation;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultViewQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewSummaryQueryService;
import de.tum.cit.aet.hephaestus.practices.review.dto.ItemEventDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewRefDTO;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

class OwnItemTraceQueryServiceTest extends BaseUnitTest {
    private static final long WORKSPACE = 11;
    private static final long ITEM = 21;
    private static final long DEVELOPER = 31;
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-23T08:00:00Z");
    private final UUID reviewId = UUID.randomUUID();
    private final ItemTraceQueryService query = mock(ItemTraceQueryService.class);
    private final ReviewSummaryQueryService reviews = mock(ReviewSummaryQueryService.class);
    private final ResultRepository results = mock(ResultRepository.class);
    private final ResultViewQuery views = mock(ResultViewQuery.class);
    private final ResultPresentation presentation = mock(ResultPresentation.class);
    private final CurrentDeveloperLookup developer = mock(CurrentDeveloperLookup.class);
    private final EvidenceAuthorization authorization = mock(EvidenceAuthorization.class);
    private final OwnItemTraceQueryService service = new OwnItemTraceQueryService(
            query,
            reviews,
            results,
            views,
            presentation,
            developer,
            authorization,
            new PracticeItemOptions(new ItemDescriptorRegistry(List.of(new PullRequestItemDescriptor()))));

    @ParameterizedTest
    @CsvSource({
        "scm.pull_request, scm.pull_request.opened, Opened",
        "scm.pull_request, scm.pull_request.retired, Activity recorded",
        "legacy.work, scm.pull_request.opened, Activity recorded"
    })
    void shouldSerializeTheCatalogLabelOrNeutralFallbackWithoutChangingEventFacts(
            String kind, String eventName, String expectedLabel) {
        var own = result(DEVELOPER, ITEM);
        var permitted = Set.of(own.getId());
        var review = givenReview(kind, ReviewOrigin.EVENT, DEVELOPER, List.of(own));
        when(authorization.permitsAll(WORKSPACE, facts(List.of(own)), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(permitted);
        givenProjection(review, List.of(own), permitted);
        when(query.events(WORKSPACE, ITEM, PageRequest.ofSize(100)))
                .thenReturn(new PageImpl<>(List.of(event(eventName))));

        var memberResponse = new JsonMapper().valueToTree(service.own(WORKSPACE, ITEM, reviewId));

        var shown = memberResponse.path("events").path(0);
        assertThat(shown.path("displayName").isString()).isTrue();
        assertThat(shown.path("displayName").asString()).isEqualTo(expectedLabel);
        assertThat(shown.path("name").asString()).isEqualTo(eventName);
        assertThat(shown.path("occurredAt").asString()).isEqualTo(OCCURRED_AT.toString());
    }

    @Test
    void shouldAuthorizeOnlyTheCurrentDevelopersResultsForTheRequestedItem() {
        var own = result(DEVELOPER, ITEM);
        givenReview(
                "scm.pull_request",
                ReviewOrigin.EVENT,
                DEVELOPER,
                List.of(own, result(DEVELOPER + 1, ITEM), result(DEVELOPER, ITEM + 1)));
        var permitted = Set.of(own.getId());
        when(authorization.permitsAll(WORKSPACE, facts(List.of(own)), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(permitted);
        when(views.views(WORKSPACE, facts(List.of(own)))).thenReturn(List.of());
        when(presentation.lists(WORKSPACE, List.of(), permitted)).thenReturn(List.of());
        when(query.events(WORKSPACE, ITEM, PageRequest.ofSize(100))).thenReturn(new PageImpl<>(List.of()));

        assertThat(service.own(WORKSPACE, ITEM, reviewId).events()).isEmpty();

        verify(authorization).permitsAll(WORKSPACE, facts(List.of(own)), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        verify(query).requireItem(WORKSPACE, ITEM);
        verify(reviews).require(WORKSPACE, reviewId);
    }

    @Test
    void shouldRefuseAnEventReviewWhenItsOwnEvidenceIsNotPermitted() {
        var own = result(DEVELOPER, ITEM);
        givenReview("scm.pull_request", ReviewOrigin.EVENT, DEVELOPER, List.of(own));
        when(authorization.permitsAll(WORKSPACE, facts(List.of(own)), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(Set.of());

        assertThatThrownBy(() -> service.own(WORKSPACE, ITEM, reviewId)).isInstanceOf(EntityNotFoundException.class);
        verifyNoInteractions(views, presentation);
    }

    @Test
    void shouldRefuseAnotherDevelopersEventReview() {
        givenReview("scm.pull_request", ReviewOrigin.EVENT, DEVELOPER, List.of(result(DEVELOPER + 1, ITEM)));
        when(authorization.permitsAll(WORKSPACE, List.of(), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(Set.of());

        assertThatThrownBy(() -> service.own(WORKSPACE, ITEM, reviewId)).isInstanceOf(EntityNotFoundException.class);
        verifyNoInteractions(views, presentation);
    }

    @Test
    void shouldKeepAnOwnRequestedReviewReadableWithoutExposingEventsWhenItHasNoVisibleResult() {
        var review = givenReview("scm.pull_request", ReviewOrigin.REQUEST, DEVELOPER, List.of());
        when(authorization.permitsAll(WORKSPACE, List.of(), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(Set.of());
        givenProjection(review, List.of(), Set.of());

        var response = new JsonMapper().valueToTree(service.own(WORKSPACE, ITEM, reviewId));

        assertThat(response.path("review").path("origin").asString()).isEqualTo("REQUEST");
        assertThat(response.path("events").size()).isZero();
        assertThat(response.path("results").size()).isZero();
    }

    @Test
    void shouldRefuseAnotherDevelopersRequestedReviewWithoutVisibleResults() {
        givenReview("scm.pull_request", ReviewOrigin.REQUEST, DEVELOPER + 1, List.of());
        when(authorization.permitsAll(WORKSPACE, List.of(), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(Set.of());

        assertThatThrownBy(() -> service.own(WORKSPACE, ITEM, reviewId)).isInstanceOf(EntityNotFoundException.class);
        verifyNoInteractions(views, presentation);
    }

    private ReviewRefDTO givenReview(String kind, ReviewOrigin origin, long requester, List<Result> rows) {
        var item = new ItemRefDTO(Long.toString(ITEM), kind, null, null, "Test work", null, null, null, null, null);
        var review = Review.builder()
                .id(reviewId)
                .workspaceId(WORKSPACE)
                .itemId(ITEM)
                .kind(ReviewKind.ITEM)
                .origin(origin)
                .requestedById(origin == ReviewOrigin.REQUEST ? requester : null)
                .build();
        when(query.requireItem(WORKSPACE, ITEM)).thenReturn(item);
        when(reviews.require(WORKSPACE, reviewId)).thenReturn(review);
        when(developer.currentDeveloperIdElseThrow()).thenReturn(DEVELOPER);
        when(results.findByWorkspaceIdAndReviewIdOrderByRecordedAtAscIdAsc(WORKSPACE, reviewId))
                .thenReturn(rows);
        return new ReviewRefDTO(
                reviewId, ReviewKind.ITEM, item, null, origin, ReviewStatus.COMPLETE, OCCURRED_AT, OCCURRED_AT);
    }

    private void givenProjection(ReviewRefDTO review, List<Result> visible, Set<UUID> permitted) {
        when(reviews.ref(any(Review.class))).thenReturn(review);
        when(views.views(WORKSPACE, facts(visible))).thenReturn(List.of());
        when(presentation.lists(WORKSPACE, List.of(), permitted)).thenReturn(List.of());
    }

    private Result result(long account, long itemId) {
        return Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(WORKSPACE)
                .reviewId(reviewId)
                .itemId(itemId)
                .developerId(account)
                .build();
    }

    private ItemEventDTO event(String name) {
        return new ItemEventDTO(
                UUID.randomUUID(),
                Long.toString(ITEM),
                name,
                "v1",
                OCCURRED_AT,
                FoundBy.WEBHOOK,
                ItemEventState.RECORDED,
                null,
                null,
                null);
    }
}
