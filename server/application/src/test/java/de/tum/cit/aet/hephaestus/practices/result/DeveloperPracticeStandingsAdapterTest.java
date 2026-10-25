package de.tum.cit.aet.hephaestus.practices.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.trend.PracticeTrendService;
import de.tum.cit.aet.hephaestus.practices.result.trend.TrendProperties;
import de.tum.cit.aet.hephaestus.practices.review.ReviewTestData;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.DeveloperPracticeStandings.Standing;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class DeveloperPracticeStandingsAdapterTest {
    private final Instant now = Instant.parse("2026-08-05T10:00:00Z");
    private final ResultViewQuery views = mock(ResultViewQuery.class);
    private final ResultVisibilityPolicy visibility = mock(ResultVisibilityPolicy.class);
    private final PracticeRepository practices = mock(PracticeRepository.class);
    private final ResultRepository results = mock(ResultRepository.class);
    private final List<ResultView> data = new ArrayList<>();
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final DeveloperPracticeStandingsAdapter adapter = new DeveloperPracticeStandingsAdapter(
            new PracticeStandingService(
                    views,
                    new JsonMapper(),
                    results,
                    mock(CurrentDeveloperLookup.class),
                    visibility,
                    practices,
                    new PracticeTrendService(new TrendProperties(), clock),
                    clock),
            clock);

    @BeforeEach
    void data() {
        when(visibility.asOneRead(anyLong(), any()))
                .thenAnswer(i -> i.<Supplier<?>>getArgument(1).get());
        when(practices.findByWorkspaceId(1L))
                .thenAnswer(i -> List.of(data.getFirst().practice()));
        when(results.forDevelopersSince(eq(1L), anyCollection(), any(Instant.class), anyBoolean()))
                .thenAnswer(i -> data.stream().map(ResultView::result).toList());
        when(views.views(eq(1L), anyCollection())).thenAnswer(i -> {
            Collection<Result> rows = i.getArgument(1);
            var ids = rows.stream().map(Result::getId).toList();
            return data.stream().filter(v -> ids.contains(v.result().getId())).toList();
        });
        lenient()
                .when(visibility.permitsHistory(eq(1L), anyCollection(), any()))
                .thenAnswer(i -> ids(i.getArgument(1)));
        lenient()
                .when(visibility.permitsAt(eq(1L), anyCollection(), any(), any()))
                .thenAnswer(i -> ids(i.getArgument(1)));
    }

    @Test
    void shouldCountTheLiveWorkAStandingWasReadOffInTheProfilesWords() {
        for (long item = 1; item <= 3; item++) {
            data.add(view(item, ReviewOrigin.EVENT));
        }

        assertThat(adapter.standings(1L, 7L)).singleElement().satisfies(standing -> {
            assertThat(standing.verdict()).isTrue();
            assertThat(standing.standing()).isEqualTo("Going well");
            assertThat(standing.decidedWork()).isEqualTo(3);
        });
    }

    @Test
    void shouldCountTheRequestedWorkAStandingWasReadOffWhenNoLiveReviewJudgedIt() {
        for (long item = 1; item <= 3; item++) {
            data.add(view(item, ReviewOrigin.REQUEST));
        }

        List<Standing> standings = adapter.standings(1L, 7L);

        assertThat(standings).singleElement().satisfies(standing -> {
            assertThat(standing.verdict()).isTrue();
            assertThat(standing.decidedWork())
                    .as("the support of the standing, not the live runs it has none of")
                    .isEqualTo(3);
        });
    }

    @Test
    void shouldNotSendAHumanOnlyStandingToTheModel() {
        data.add(view(1, ReviewOrigin.EVENT));
        lenient()
                .when(visibility.permitsStandingAt(eq(1L), anyCollection(), any()))
                .thenAnswer(i -> ids(i.getArgument(1)));
        when(visibility.permitsAt(eq(1L), anyCollection(), any(), any())).thenReturn(Set.of());

        assertThat(adapter.standings(1L, 7L)).singleElement().satisfies(standing -> {
            assertThat(standing.verdict()).isFalse();
            assertThat(standing.standing()).isEqualTo("Not reviewed");
            assertThat(standing.decidedWork()).isZero();
            assertThat(standing.toWorkOn()).isZero();
            assertThat(standing.strengths()).isZero();
            assertThat(standing.direction()).isNull();
        });
    }

    private ResultView view(long item, ReviewOrigin origin) {
        Instant at = now.minus(Duration.ofDays(10 - item));
        var view =
                ReviewTestData.view(item, UUID.randomUUID(), at, new ItemKind("scm.pull_request"), ResultOutcome.MET);
        var review = Review.builder()
                .id(view.review().getId())
                .workspaceId(1)
                .itemId(item)
                .kind(ReviewKind.ITEM)
                .origin(origin)
                .status(ReviewStatus.COMPLETE)
                .itemVersion("version-1")
                .orderedAt(at)
                .createdAt(at)
                .completedAt(at);
        if (origin == ReviewOrigin.EVENT) review.eventId(UUID.randomUUID());
        return new ResultView(view.result(), view.practice(), view.version(), review.build(), view.item());
    }

    private static Object ids(Collection<Result> rows) {
        return rows.stream().map(Result::getId).collect(Collectors.toSet());
    }
}
