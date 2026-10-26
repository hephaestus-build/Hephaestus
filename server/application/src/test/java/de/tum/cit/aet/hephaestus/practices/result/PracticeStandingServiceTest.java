package de.tum.cit.aet.hephaestus.practices.result;

import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.resultBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.result.dto.PracticeStandingResultDTO;
import de.tum.cit.aet.hephaestus.practices.result.trend.PracticeTrendService;
import de.tum.cit.aet.hephaestus.practices.result.trend.TrendProperties;
import de.tum.cit.aet.hephaestus.practices.review.ReviewTestData;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStanding;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.StandingBasis;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class PracticeStandingServiceTest {
    private final Instant now = Instant.parse("2026-08-05T10:00:00Z");
    private final ResultViewQuery views = mock(ResultViewQuery.class);
    private final CurrentDeveloperLookup developer = mock(CurrentDeveloperLookup.class);
    private final ResultVisibilityPolicy visibility = mock(ResultVisibilityPolicy.class);
    private final PracticeRepository practices = mock(PracticeRepository.class);
    private final ResultRepository results = mock(ResultRepository.class);
    private final List<ResultView> data = new ArrayList<>();
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final PracticeStandingService service = new PracticeStandingService(
            views,
            new JsonMapper(),
            results,
            developer,
            visibility,
            practices,
            new PracticeTrendService(new TrendProperties(), clock),
            clock);

    @BeforeEach
    void data() {
        when(visibility.asOneRead(anyLong(), any()))
                .thenAnswer(i -> i.<Supplier<?>>getArgument(1).get());
        when(developer.currentDeveloperId()).thenReturn(Optional.of(7L));
        when(practices.findByWorkspaceId(1L))
                .thenAnswer(i -> data.isEmpty()
                        ? List.of(ReviewTestData.practice(1, 1, "test-practice"))
                        : List.of(data.getFirst().practice()));
        when(results.forDevelopersSince(eq(1L), anyCollection(), any(Instant.class), anyBoolean()))
                .thenAnswer(i -> data.stream().map(ResultView::result).toList());
        when(views.views(eq(1L), anyCollection())).thenAnswer(i -> {
            Collection<Result> rows = i.getArgument(1);
            var ids = rows.stream().map(Result::getId).toList();
            return data.stream().filter(v -> ids.contains(v.result().getId())).toList();
        });
        Mockito.lenient()
                .when(visibility.permitsHistory(eq(1L), anyCollection(), any()))
                .thenAnswer(i -> {
                    Collection<Result> rows = i.getArgument(1);
                    return rows.stream().map(Result::getId).collect(Collectors.toSet());
                });
        Mockito.lenient()
                .when(visibility.permitsAt(eq(1L), anyCollection(), any(), any()))
                .thenAnswer(i -> {
                    Collection<Result> rows = i.getArgument(1);
                    return rows.stream().map(Result::getId).collect(Collectors.toSet());
                });
        Mockito.lenient()
                .when(visibility.permitsStandingAt(eq(1L), anyCollection(), any()))
                .thenAnswer(i -> {
                    Collection<Result> rows = i.getArgument(1);
                    return rows.stream().map(Result::getId).collect(Collectors.toSet());
                });
        Mockito.lenient()
                .when(visibility.permitsAll(eq(1L), anyCollection(), any()))
                .thenAnswer(i -> {
                    Collection<Result> rows = i.getArgument(1);
                    return rows.stream().map(Result::getId).collect(Collectors.toSet());
                });
    }

    @Test
    void recentCleanWorkAcknowledgesAnOlderProblem() {
        add(1, ResultOutcome.NOT_MET);
        add(2, ResultOutcome.MET);
        add(3, ResultOutcome.MET);
        assertThat(card().standing()).isEqualTo(PracticeStanding.STRENGTH);
    }

    @Test
    void oneCleanOpportunityDoesNotRestoreStrength() {
        add(1, ResultOutcome.NOT_MET);
        add(2, ResultOutcome.MET);
        assertThat(card().standing()).isEqualTo(PracticeStanding.MIXED);
    }

    @Test
    void aRecentProblemRemainsVisibleAlongsideOlderStrengths() {
        add(1, ResultOutcome.MET);
        add(2, ResultOutcome.MET);
        add(3, ResultOutcome.MET);
        add(4, ResultOutcome.NOT_MET);
        assertThat(card().standing()).isEqualTo(PracticeStanding.MIXED);
        assertThat(card().toWorkOn()).hasSize(1);
        assertThat(card().strengths()).hasSize(3);
    }

    @Test
    void aVerdictlessReviewIsDifferentFromNoReview() {
        assertThat(card().standing()).isEqualTo(PracticeStanding.NOT_OBSERVED);
        add(1, ResultOutcome.DOES_NOT_APPLY);
        assertThat(card().standing()).isEqualTo(PracticeStanding.NO_OPPORTUNITY);
        assertThat(card().doesNotApplyOnly()).isTrue();
    }

    @Test
    void feedbackModeOffStillContributesResults() {
        add(1, ResultOutcome.MET);
        data.getFirst().practice().setFeedbackMode(FeedbackMode.OFF);
        assertThat(card().standing()).isEqualTo(PracticeStanding.STRENGTH);
    }

    @Test
    void anEndedClaimDoesNotBecomeCurrentAgain() {
        add(1, ResultOutcome.NOT_MET);
        var prior = data.getFirst();
        prior.result().end(ResultEndReason.REPLACED, now.minusSeconds(1));
        add(2, ResultOutcome.MET);
        var latest = data.getLast();
        latest.review().setItemId(prior.result().getItemId());
        data.set(
                data.size() - 1,
                new ResultView(
                        resultBuilder(latest.result())
                                .itemId(prior.result().getItemId())
                                .build(),
                        latest.practice(),
                        latest.version(),
                        latest.review(),
                        prior.item()));
        assertThat(card().toWorkOn()).isEmpty();
        assertThat(card().strengths()).hasSize(1);
    }

    @Test
    void keepsRequestedEvidenceSeparateFromEventEvidence() {
        add(1, ResultOutcome.NOT_MET);
        var eventStanding = card().standing();
        add(2, ResultOutcome.MET);
        data.getLast().review().setOrigin(ReviewOrigin.REQUEST);
        var snapshot = service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst();
        var standing = snapshot.practices().values().iterator().next();
        assertThat(standing.dto().standing()).isEqualTo(eventStanding);
        assertThat(standing.evidence()).hasSize(2);
        assertThat(standing.liveRuns())
                .extracting(view -> view.review().getOrigin())
                .containsExactly(ReviewOrigin.EVENT);
    }

    @Test
    void theWindowDoesNotCountEvidenceRecordedAfterItsEdge() {
        add(1, ResultOutcome.MET);
        var edge = data.getFirst().result().getRecordedAt();
        add(2, ResultOutcome.NOT_MET);
        var snapshots = service.getStandingSnapshots(1L, 7L, List.of(edge, now));
        assertThat(snapshots.getFirst().dtos().getFirst().standing()).isEqualTo(PracticeStanding.STRENGTH);
        assertThat(snapshots.getLast().dtos().getFirst().standing()).isEqualTo(PracticeStanding.MIXED);
    }

    @Test
    void shouldKeepTheLiveStandingWhenARequestedReviewReplacesItsClaim() {
        add(1, ResultOutcome.NOT_MET);
        var live = data.getFirst();
        var before = card().standing();
        var requested = replace(live, 5, ResultOutcome.MET, ReviewOrigin.REQUEST, ResultEndReason.REPLACED);

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.dto().standing()).isEqualTo(before);
        assertThat(standing.dto().basis()).isEqualTo(StandingBasis.CURRENT_REVIEWS);
        assertThat(standing.liveRuns()).extracting(ResultView::result).containsExactly(live.result());
        // The quote follows the current claim: the clean re-review, not the replaced problem.
        assertThat(standing.dto().toWorkOn()).isEmpty();
        assertThat(standing.dto().strengths())
                .extracting(PracticeStandingResultDTO::resultId)
                .containsExactly(requested.result().getId());
        assertThat(live.result().getEndReason()).isEqualTo(ResultEndReason.REPLACED);
    }

    @Test
    void shouldKeepTheLiveStandingWhenARequestedReviewReachesNoVerdict() {
        add(1, ResultOutcome.NOT_MET);
        var before = card().standing();
        replace(data.getFirst(), 5, ResultOutcome.DOES_NOT_APPLY, ReviewOrigin.REQUEST, ResultEndReason.REPLACED);

        assertThat(card().standing()).isEqualTo(before);
        assertThat(card().basis()).isEqualTo(StandingBasis.CURRENT_REVIEWS);
    }

    @Test
    void shouldKeepTheLiveRunWhenTheWorkChangedBeforeTheRequest() {
        add(1, ResultOutcome.NOT_MET);
        var live = data.getFirst();
        replace(live, 5, ResultOutcome.MET, ReviewOrigin.REQUEST, ResultEndReason.WORK_CHANGED);

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.liveRuns()).extracting(ResultView::result).containsExactly(live.result());
        assertThat(standing.dto().toWorkOn()).isEmpty();
    }

    @Test
    void shouldKeepTheLiveStandingWhenAWorkChangeStartedNoReviewOfThePractice() {
        add(1, ResultOutcome.NOT_MET);
        var live = data.getFirst();
        var before = card().standing();
        live.result().end(ResultEndReason.WORK_CHANGED, now.minusSeconds(1));

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.dto().standing()).isEqualTo(before);
        assertThat(standing.dto().basis()).isEqualTo(StandingBasis.CURRENT_REVIEWS);
        assertThat(standing.liveRuns()).extracting(ResultView::result).containsExactly(live.result());
        // The ended result counts, but it is no current proof to quote.
        assertThat(standing.dto().toWorkOn()).isEmpty();
    }

    @Test
    void shouldPreferANewerLiveRunOverTheOneAWorkChangeEnded() {
        add(1, ResultOutcome.NOT_MET);
        var newer = replace(data.getFirst(), 5, ResultOutcome.MET, ReviewOrigin.EVENT, ResultEndReason.WORK_CHANGED);

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.liveRuns()).extracting(ResultView::result).containsExactly(newer.result());
    }

    @ParameterizedTest
    @EnumSource(
            value = ResultEndReason.class,
            names = {"REPAIRED", "REMOVED"})
    void shouldEndTheLiveRunAtARepairOrRemoval(ResultEndReason reason) {
        add(1, ResultOutcome.NOT_MET);
        data.getFirst().result().end(reason, now.minusSeconds(1));

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.dto().standing()).isEqualTo(PracticeStanding.NOT_OBSERVED);
        assertThat(standing.liveRuns()).isEmpty();
    }

    @Test
    void shouldSkipAnOutrankedResultBetweenAWorkChangeAndARequest() {
        add(1, ResultOutcome.NOT_MET);
        var live = data.getFirst();
        var outranked = ReviewTestData.view(
                live.result().getItemId(),
                UUID.randomUUID(),
                now.minus(Duration.ofDays(7)),
                new ItemKind("scm.pull_request"),
                ResultOutcome.MET);
        outranked.result().end(ResultEndReason.OUTRANKED, outranked.result().getRecordedAt());
        data.add(outranked);
        replace(live, 5, ResultOutcome.MET, ReviewOrigin.REQUEST, ResultEndReason.WORK_CHANGED);

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.liveRuns()).extracting(ResultView::result).containsExactly(live.result());
    }

    @Test
    void shouldNotReachPastARepairToAnEarlierWorkChange() {
        add(1, ResultOutcome.NOT_MET);
        var repaired = replace(data.getFirst(), 3, ResultOutcome.MET, ReviewOrigin.EVENT, ResultEndReason.WORK_CHANGED);
        replace(repaired, 5, ResultOutcome.MET, ReviewOrigin.REQUEST, ResultEndReason.REPAIRED);

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.liveRuns()).isEmpty();
    }

    @Test
    void shouldPreferANewerLiveRunOverTheOneARequestReplaced() {
        add(1, ResultOutcome.NOT_MET);
        var requested = replace(data.getFirst(), 4, ResultOutcome.MET, ReviewOrigin.REQUEST, ResultEndReason.REPLACED);
        var newer = replace(requested, 6, ResultOutcome.MET, ReviewOrigin.EVENT, ResultEndReason.REPLACED);

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.liveRuns()).extracting(ResultView::result).containsExactly(newer.result());
    }

    @Test
    void shouldNotLetAnOutrankedResultHoldOrBreakTheClaim() {
        add(2, ResultOutcome.NOT_MET);
        var live = data.getFirst();
        var late = ReviewTestData.view(
                live.result().getItemId(),
                UUID.randomUUID(),
                now.minus(Duration.ofDays(9)),
                new ItemKind("scm.pull_request"),
                ResultOutcome.MET);
        late.result().end(ResultEndReason.OUTRANKED, late.result().getRecordedAt());
        data.add(late);

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.liveRuns()).extracting(ResultView::result).containsExactly(live.result());
    }

    @Test
    void shouldReadEarlierReviewsQualifiedWhenNoCurrentReviewReachedThePractice() {
        add(1, ResultOutcome.NOT_MET);
        add(2, ResultOutcome.MET);
        earlier(0);
        earlier(1);

        var card = card();
        assertThat(StandingScale.isVerdict(card.standing())).isTrue();
        assertThat(card.basis()).isEqualTo(StandingBasis.EARLIER_REVIEWS);
        assertThat(card.toWorkOn()).isEmpty();
        assertThat(card.strengths()).isEmpty();
        assertThat(card.direction()).isNull();
        assertThat(card.trendSupport()).isNull();
    }

    @Test
    void shouldSayTheCriteriaAreUnknownWhenAnEarlierReviewNamesNoPracticeVersion() {
        add(1, ResultOutcome.NOT_MET);
        add(2, ResultOutcome.MET);
        earlier(0);
        var view = data.get(1);
        data.set(
                1,
                new ResultView(
                        resultBuilder(view.result())
                                .evidenceVersion((short) 0)
                                .practiceVersionId(null)
                                .build(),
                        view.practice(),
                        null,
                        view.review(),
                        view.item()));

        assertThat(card().basis()).isEqualTo(StandingBasis.EARLIER_REVIEWS_CRITERIA_UNKNOWN);
        assertThat(card().toWorkOn()).isEmpty();
    }

    @Test
    void shouldPreferCurrentReviewsOverEarlierReviewsOfThePractice() {
        add(1, ResultOutcome.NOT_MET);
        earlier(0);
        add(2, ResultOutcome.MET);

        assertThat(card().basis()).isEqualTo(StandingBasis.CURRENT_REVIEWS);
        assertThat(card().toWorkOn()).isEmpty();
    }

    @Test
    void shouldRetainEarlierLiveAssessmentWhenARequestedReviewHasNoVerdict() {
        add(1, ResultOutcome.NOT_MET);
        earlier(0);
        var before = card().standing();
        replace(data.getFirst(), 5, ResultOutcome.DOES_NOT_APPLY, ReviewOrigin.REQUEST, ResultEndReason.REPLACED);

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.dto().standing()).isEqualTo(before);
        assertThat(standing.dto().basis()).isEqualTo(StandingBasis.EARLIER_REVIEWS);
        assertThat(standing.dto().toWorkOn()).isEmpty();
        assertThat(standing.dto().strengths()).isEmpty();
        assertThat(standing.dto().direction()).isNull();
        assertThat(standing.dto().trendSupport()).isNull();
        assertThat(standing.liveRuns()).isEmpty();
    }

    @Test
    void shouldNotResurrectAnEarlierReviewThatACurrentReviewReplaced() {
        add(1, ResultOutcome.NOT_MET);
        earlier(0);
        replace(data.getFirst(), 5, ResultOutcome.DOES_NOT_APPLY, ReviewOrigin.EVENT, ResultEndReason.REPLACED);

        assertThat(card().standing()).isEqualTo(PracticeStanding.NO_OPPORTUNITY);
        assertThat(card().basis()).isNull();
    }

    @Test
    void shouldCountAStandingWithoutQuotesWhenDeliveryControlsRefuseTheRows() {
        add(1, ResultOutcome.NOT_MET);
        var before = card().standing();
        assertThat(before).isEqualTo(PracticeStanding.MIXED);
        when(visibility.permitsAt(eq(1L), anyCollection(), any(), any())).thenReturn(Set.of());

        assertThat(card().standing()).isEqualTo(before);
        assertThat(card().toWorkOn()).isEmpty();
    }

    @Test
    void shouldReadNoStandingWhenTheStandingFencesRefuseTheRows() {
        add(1, ResultOutcome.NOT_MET);
        when(visibility.permitsStandingAt(eq(1L), anyCollection(), any())).thenReturn(Set.of());

        assertThat(card().standing()).isEqualTo(PracticeStanding.NOT_OBSERVED);
    }

    @Test
    void shouldReadNoStandingWhenTheStandingFencesRefuseARowThatAWorkChangeEnded() {
        add(1, ResultOutcome.NOT_MET);
        data.getFirst().result().end(ResultEndReason.WORK_CHANGED, now.minusSeconds(1));
        assertThat(card().standing()).isEqualTo(PracticeStanding.MIXED);
        when(visibility.permitsStandingAt(eq(1L), anyCollection(), any())).thenReturn(Set.of());

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.dto().standing()).isEqualTo(PracticeStanding.NOT_OBSERVED);
        assertThat(standing.liveRuns()).isEmpty();
    }

    @Test
    void shouldReadTheProfilesStandingForEachDeveloperWhenReadForTheWorkspace() {
        add(1, ResultOutcome.NOT_MET);
        add(2, ResultOutcome.MET);
        add(3, ResultOutcome.MET);
        replace(data.getFirst(), 5, ResultOutcome.MET, ReviewOrigin.REQUEST, ResultEndReason.REPLACED);

        var workspace = service.getCurrentWorkspaceStandings(1L, List.of(7L, 8L));
        var reader = Objects.requireNonNull(workspace.byDeveloper().get(7L));
        var other = Objects.requireNonNull(workspace.byDeveloper().get(8L));
        var profile = service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst();
        // Counts quote nothing; every standing, basis, trend and share equals the profile's.
        assertThat(reader.practices().values())
                .extracting(
                        practice -> practice.dto().standing(),
                        practice -> practice.dto().basis(),
                        practice -> practice.dto().direction(),
                        practice -> practice.share())
                .containsExactlyElementsOf(profile.practices().values().stream()
                        .map(practice -> tuple(
                                practice.dto().standing(),
                                practice.dto().basis(),
                                practice.dto().direction(),
                                practice.share()))
                        .toList());
        assertThat(reader.dtos()).allSatisfy(dto -> assertThat(dto.toWorkOn()).isEmpty());
        assertThat(other.dtos()).extracting(PracticeStandingDTO::standing).containsOnly(PracticeStanding.NOT_OBSERVED);
    }

    private PracticeStandingService.StandingSnapshot.PracticeStandingSnapshot only(
            PracticeStandingService.StandingSnapshot snapshot) {
        return snapshot.practices().values().iterator().next();
    }

    // Writes what ResultClaimWriter writes when a later review claims the same work: the prior claim ends at the
    // later recording.
    private ResultView replace(
            ResultView prior, long day, ResultOutcome outcome, ReviewOrigin origin, ResultEndReason reason) {
        var next = ReviewTestData.view(
                prior.result().getItemId(),
                UUID.randomUUID(),
                now.minus(Duration.ofDays(10 - day)),
                new ItemKind("scm.pull_request"),
                outcome);
        next.review().setOrigin(origin);
        prior.result().end(reason, next.result().getRecordedAt());
        data.add(next);
        return next;
    }

    // A result the cutover carried over from the earlier model: kept as recorded, without current proof.
    private void earlier(int index) {
        var view = data.get(index);
        data.set(
                index,
                new ResultView(
                        resultBuilder(view.result()).evidenceVersion((short) 0).build(),
                        view.practice(),
                        view.version(),
                        view.review(),
                        view.item()));
    }

    private PracticeStandingDTO card() {
        return service.getStandings(1L).getFirst();
    }

    private void add(long id, ResultOutcome outcome) {
        data.add(ReviewTestData.view(
                id, UUID.randomUUID(), now.minus(Duration.ofDays(10 - id)), new ItemKind("scm.pull_request"), outcome));
    }
}
