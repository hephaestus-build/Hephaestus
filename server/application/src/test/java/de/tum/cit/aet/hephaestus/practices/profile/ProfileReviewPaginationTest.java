package de.tum.cit.aet.hephaestus.practices.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultPresentation;
import de.tum.cit.aet.hephaestus.practices.result.ResultQueries;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultView;
import de.tum.cit.aet.hephaestus.practices.result.ResultViewQuery;
import de.tum.cit.aet.hephaestus.practices.result.dto.ResultListDTO;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewSummaryQueryService;
import de.tum.cit.aet.hephaestus.practices.review.ReviewTestData;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewRefDTO;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.trace.OwnItemTraceQueryService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

class ProfileReviewPaginationTest extends BaseUnitTest {
    final CurrentDeveloperLookup developer = mock(CurrentDeveloperLookup.class);
    final ResultRepository results = mock(ResultRepository.class);
    final ResultViewQuery views = mock(ResultViewQuery.class);
    final ResultPresentation presentation = mock(ResultPresentation.class);
    final ReviewSummaryQueryService reviews = mock(ReviewSummaryQueryService.class);
    final EvidenceAuthorization authorization = mock(EvidenceAuthorization.class);
    final PracticeProfileReviewService service = new PracticeProfileReviewService(
            developer,
            results,
            views,
            presentation,
            reviews,
            mock(OwnItemTraceQueryService.class),
            authorization,
            mock(ProductItemRepository.class));

    @Test
    void shouldKeepReviewsWithHiddenTextInsidePageBoundaries() {
        assertPage(10, Set.of(3, 4, 5), 0, 3, List.of(0, 1, 2), true);
        assertPage(10, Set.of(3, 4, 5), 1, 3, List.of(3, 4, 5), true);
        assertPage(10, Set.of(3, 4, 5), 3, 3, List.of(9), false);
    }

    @Test
    void shouldPageThroughRetainedReviewsRegardlessOfTextVisibility() {
        var hidden = IntStream.range(0, 120).filter(i -> i % 2 == 1).boxed().collect(Collectors.toSet());
        assertPage(120, hidden, 3, 10, List.of(30, 31, 32, 33, 34, 35, 36, 37, 38, 39), true);
    }

    @Test
    void shouldRetainTheLastPageOfReviewsWithHiddenText() {
        var hidden = IntStream.range(5, 80).boxed().collect(Collectors.toSet());
        assertPage(80, hidden, 0, 5, List.of(0, 1, 2, 3, 4), true);
        assertPage(80, hidden, 15, 5, List.of(75, 76, 77, 78, 79), false);
    }

    @Test
    void shouldReadOnlyTheTimeframeAndCountOnlyThePageWhenASevenDayPageIsAsked() {
        var now = Instant.parse("2026-10-14T00:00:00Z");
        var since = now.minus(Duration.ofDays(7));
        stub(12, Set.of(), now, since);
        var page = service.list(
                context(), new OwnReviewFilter(null, null, null, null, since, false), PageRequest.of(1, 5));

        assertThat(page.getContent()).hasSize(5);
        var shownReviews = page.getContent().stream().map(p -> p.review().id()).collect(Collectors.toSet());
        // The timeframe bounds the read itself, not a filter over the whole history.
        verify(results).forDevelopersSince(eq(1L), eq(List.of(7L)), eq(since), eq(false));
        verify(results, never()).forDevelopersSince(anyLong(), anyCollection(), eq(Instant.EPOCH), anyBoolean());
        ArgumentCaptor<Collection<UUID>> counted = ArgumentCaptor.captor();
        verify(results).placesOnWork(eq(1L), eq(7L), counted.capture());
        assertThat(counted.getValue()).containsExactlyInAnyOrderElementsOf(shownReviews);
        ArgumentCaptor<Collection<Result>> checked = ArgumentCaptor.captor();
        verify(authorization).permitsAll(eq(1L), checked.capture(), any());
        assertThat(checked.getValue())
                .extracting(Result::getReviewId)
                .containsExactlyInAnyOrderElementsOf(shownReviews);
    }

    void assertPage(int count, Set<Integer> hidden, int number, int size, List<Integer> expected, boolean hasNext) {
        var now = Instant.parse("2026-10-14T00:00:00Z");
        stub(count, hidden, now, Instant.EPOCH);
        var page = service.list(
                context(), new OwnReviewFilter(null, null, null, null, null, false), PageRequest.of(number, size));
        assertThat(page.getContent())
                .extracting(p -> Integer.parseInt(p.review().item().id()) - 1)
                .containsExactlyElementsOf(expected);
        assertThat(page.hasNext()).isEqualTo(hasNext);
        assertThat(page.getTotalElements()).isEqualTo(count);
    }

    static WorkspaceContext context() {
        return new WorkspaceContext(1L, "ws", "Workspace", AccountType.ORG, null, Set.of());
    }

    /** {@code count} reviews of one result each, one work apiece, read from {@code since}. */
    List<ResultView> stub(int count, Set<Integer> hidden, Instant now, Instant since) {
        reset(results, views, presentation, reviews, authorization, developer);
        var data = IntStream.range(0, count)
                .mapToObj(i -> ReviewTestData.view(
                        i + 1,
                        UUID.randomUUID(),
                        now.minusSeconds(i),
                        new ItemKind("scm.pull_request"),
                        ResultOutcome.MET))
                .toList();
        var visible = IntStream.range(0, count)
                .filter(i -> !hidden.contains(i))
                .mapToObj(i -> data.get(i).result().getId())
                .collect(Collectors.toSet());
        when(developer.currentDeveloperId()).thenReturn(Optional.of(7L));
        when(results.forDevelopersSince(eq(1L), eq(List.of(7L)), eq(since), eq(false)))
                .thenReturn(data.stream().map(ResultView::result).toList());
        when(results.placesOnWork(eq(1L), eq(7L), anyCollection())).thenAnswer(i -> {
            Collection<UUID> ids = i.getArgument(2);
            return ids.stream().collect(Collectors.toMap(id -> id, id -> new ResultQueries.PlaceOnWork(1, 1)));
        });
        when(authorization.permitsAll(eq(1L), anyCollection(), any())).thenReturn(visible);
        when(views.views(eq(1L), anyCollection())).thenAnswer(i -> {
            Collection<Result> rows = i.getArgument(1);
            var ids = rows.stream().map(r -> r.getId()).toList();
            return data.stream().filter(v -> ids.contains(v.result().getId())).toList();
        });
        when(presentation.lists(eq(1L), anyCollection(), any())).thenAnswer(i -> {
            Collection<ResultView> selected = i.getArgument(1);
            return selected.stream()
                    .map(v -> {
                        var r = v.result();
                        return new ResultListDTO(
                                r.getId(),
                                r.getReviewId(),
                                v.item(),
                                v.practice().getSlug(),
                                "Practice",
                                r.getOutcome(),
                                null,
                                null,
                                ReviewOrigin.EVENT,
                                r.getRecordedAt(),
                                Currentness.CURRENT,
                                null,
                                null,
                                List.of());
                    })
                    .toList();
        });
        when(presentation.ownFeedback(any())).thenReturn(List.of());
        when(reviews.ref(any())).thenAnswer(i -> {
            Review r = i.getArgument(0);
            var v = data.stream()
                    .filter(d -> d.review().getId().equals(r.getId()))
                    .findFirst()
                    .orElseThrow();
            return new ReviewRefDTO(
                    r.getId(),
                    r.getKind(),
                    v.item(),
                    r.getItemVersion(),
                    r.getOrigin(),
                    r.getStatus(),
                    r.getCreatedAt(),
                    r.getCompletedAt());
        });
        return data;
    }
}
