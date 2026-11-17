package de.tum.cit.aet.hephaestus.practices.result;

import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.ended;
import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.migrated;
import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.onItem;
import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.withoutPracticeVersion;
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
import de.tum.cit.aet.hephaestus.practices.model.ResultFacts;
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
        when(views.viewsOnDemand(eq(1L), anyCollection())).thenAnswer(i -> {
            Collection<ResultFacts> rows = i.getArgument(1);
            var ids = rows.stream().map(ResultFacts::id).toList();
            return data.stream().filter(v -> ids.contains(v.result().id())).toList();
        });
        Mockito.lenient()
                .when(visibility.permitsHistory(eq(1L), anyCollection(), any()))
                .thenAnswer(i -> {
                    Collection<ResultFacts> rows = i.getArgument(1);
                    return rows.stream().map(ResultFacts::id).collect(Collectors.toSet());
                });
        Mockito.lenient()
                .when(visibility.permitsAt(eq(1L), anyCollection(), any(), any()))
                .thenAnswer(i -> {
                    Collection<ResultFacts> rows = i.getArgument(1);
                    return rows.stream().map(ResultFacts::id).collect(Collectors.toSet());
                });
        Mockito.lenient()
                .when(visibility.permitsStandingAt(eq(1L), anyCollection(), any()))
                .thenAnswer(i -> {
                    Collection<ResultFacts> rows = i.getArgument(1);
                    return rows.stream().map(ResultFacts::id).collect(Collectors.toSet());
                });
        Mockito.lenient()
                .when(visibility.permitsAll(eq(1L), anyCollection(), any()))
                .thenAnswer(i -> {
                    Collection<ResultFacts> rows = i.getArgument(1);
                    return rows.stream().map(ResultFacts::id).collect(Collectors.toSet());
                });
    }

    @Test
    void shouldQuoteTheNextCurrentProblemWhenTheFirstOneReadsStale() {
        for (long day = 1; day <= 6; day++) add(day, ResultOutcome.NOT_MET);
        var first = card().toWorkOn().getFirst().resultId();
        withFreshness(first, Freshness.stale(null));

        var quoted = card().toWorkOn().stream()
                .map(PracticeStandingResultDTO::resultId)
                .toList();
        assertThat(quoted).hasSize(5).doesNotContain(first);
    }

    @Test
    void shouldNotQuoteAStrengthWhoseWorkCannotBeRead() {
        for (long day = 1; day <= 3; day++) add(day, ResultOutcome.MET);
        var unreadable = data.getFirst().result().id();
        withFreshness(unreadable, Freshness.recordMissing());

        assertThat(card().strengths())
                .extracting(PracticeStandingResultDTO::resultId)
                .hasSize(2)
                .doesNotContain(unreadable);
    }

    @Test
    void shouldLeaveAStaleResultOutOfTheStandingAndTheQuotesOfModelContext() {
        add(1, ResultOutcome.NOT_MET);
        add(2, ResultOutcome.MET);
        var stale = data.getFirst().result().id();
        withFreshness(stale, Freshness.stale(null));

        var standing = only(
                service.getProcessingStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.evidence()).extracting(view -> view.result().id()).doesNotContain(stale);
        assertThat(standing.dto().toWorkOn()).isEmpty();
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
    void shouldKeepCountingAnObservationRecordedBeforeThePracticeWasSetToFeedbackModeOff() {
        add(1, ResultOutcome.MET);
        data.getFirst().practice().setFeedbackMode(FeedbackMode.OFF);
        assertThat(card().standing()).isEqualTo(PracticeStanding.STRENGTH);
    }

    @Test
    void shouldGiveNoStandingAndQuoteNothingWhenEveryObservationWasSealedInFeedbackModeOff() {
        add(1, ResultOutcome.NOT_MET);
        data.set(0, sealedIn(data.getFirst(), FeedbackMode.OFF));

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.dto().standing()).isEqualTo(PracticeStanding.NOT_OBSERVED);
        assertThat(standing.dto().toWorkOn()).isEmpty();
        assertThat(standing.dto().strengths()).isEmpty();
        assertThat(standing.evidence()).isEmpty();
    }

    @Test
    void anEndedClaimDoesNotBecomeCurrentAgain() {
        add(1, ResultOutcome.NOT_MET);
        var prior = data.getFirst();
        end(prior, ResultEndReason.REPLACED, now.minusSeconds(1));
        add(2, ResultOutcome.MET);
        var latest = data.getLast();
        latest.review().setItemId(prior.result().itemId());
        data.set(
                data.size() - 1,
                new ResultView(
                        onItem(latest.result(), prior.result().itemId()),
                        latest.practice(),
                        latest.version(),
                        latest.review(),
                        prior.item(),
                        ResultTestFixtures.fixedFreshness(Freshness.current(null)),
                        latest.sealedFeedbackMode()));
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
        var edge = data.getFirst().result().recordedAt();
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
        assertThat(standing.liveRuns())
                .extracting(view -> view.result().id())
                .containsExactly(live.result().id());
        // The quote follows the current claim: the clean re-review, not the replaced problem.
        assertThat(standing.dto().toWorkOn()).isEmpty();
        assertThat(standing.dto().strengths())
                .extracting(PracticeStandingResultDTO::resultId)
                .containsExactly(requested.result().id());
        // Records are values: the replaced claim is the list's current entry, not the copy held in `live`.
        assertThat(data)
                .filteredOn(view -> view.result().id().equals(live.result().id()))
                .singleElement()
                .extracting(view -> view.result().endReason())
                .isEqualTo(ResultEndReason.REPLACED);
    }

    @Test
    void shouldNeitherCountNorHideTheLiveStandingWithAnObservationSealedInFeedbackModeOff() {
        add(1, ResultOutcome.NOT_MET);
        var live = data.getFirst();
        var before = card().standing();
        replace(live, 5, ResultOutcome.MET, ReviewOrigin.EVENT, ResultEndReason.REPLACED);
        data.set(1, sealedIn(data.get(1), FeedbackMode.OFF));

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.dto().standing()).isEqualTo(before);
        assertThat(standing.liveRuns())
                .extracting(view -> view.result().id())
                .containsExactly(live.result().id());
        assertThat(standing.dto().strengths()).isEmpty();
    }

    @Test
    void shouldCountAnObservationSealedLiveAfterThePracticeLeftFeedbackModeOff() {
        add(1, ResultOutcome.NOT_MET);
        data.set(0, sealedIn(data.getFirst(), FeedbackMode.OFF));
        replace(data.getFirst(), 5, ResultOutcome.MET, ReviewOrigin.EVENT, ResultEndReason.REPLACED);
        data.set(1, sealedIn(data.get(1), FeedbackMode.AUTOMATIC));

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.liveRuns())
                .extracting(ResultView::result)
                .containsExactly(data.get(1).result());
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
        assertThat(standing.liveRuns())
                .extracting(view -> view.result().id())
                .containsExactly(live.result().id());
        assertThat(standing.dto().toWorkOn()).isEmpty();
    }

    @Test
    void shouldKeepTheLiveStandingWhenAWorkChangeStartedNoReviewOfThePractice() {
        add(1, ResultOutcome.NOT_MET);
        var live = data.getFirst();
        var before = card().standing();
        end(live, ResultEndReason.WORK_CHANGED, now.minusSeconds(1));

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.dto().standing()).isEqualTo(before);
        assertThat(standing.dto().basis()).isEqualTo(StandingBasis.CURRENT_REVIEWS);
        assertThat(standing.liveRuns())
                .extracting(view -> view.result().id())
                .containsExactly(live.result().id());
        // The ended result counts, but it is no current proof to quote.
        assertThat(standing.dto().toWorkOn()).isEmpty();
    }

    @Test
    void shouldPreferANewerLiveRunOverTheOneAWorkChangeEnded() {
        add(1, ResultOutcome.NOT_MET);
        var newer = replace(data.getFirst(), 5, ResultOutcome.MET, ReviewOrigin.EVENT, ResultEndReason.WORK_CHANGED);

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.liveRuns())
                .extracting(view -> view.result().id())
                .containsExactly(newer.result().id());
    }

    @ParameterizedTest
    @EnumSource(
            value = ResultEndReason.class,
            names = {"REPAIRED", "REMOVED"})
    void shouldEndTheLiveRunAtARepairOrRemoval(ResultEndReason reason) {
        add(1, ResultOutcome.NOT_MET);
        end(data.getFirst(), reason, now.minusSeconds(1));

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.dto().standing()).isEqualTo(PracticeStanding.NOT_OBSERVED);
        assertThat(standing.liveRuns()).isEmpty();
    }

    @Test
    void shouldSkipAnOutrankedResultBetweenAWorkChangeAndARequest() {
        add(1, ResultOutcome.NOT_MET);
        var live = data.getFirst();
        var outranked = ReviewTestData.view(
                live.result().itemId(),
                UUID.randomUUID(),
                now.minus(Duration.ofDays(7)),
                new ItemKind("scm.pull_request"),
                ResultOutcome.MET);
        data.add(ended(outranked, ResultEndReason.OUTRANKED, outranked.result().recordedAt()));
        replace(live, 5, ResultOutcome.MET, ReviewOrigin.REQUEST, ResultEndReason.WORK_CHANGED);

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.liveRuns())
                .extracting(view -> view.result().id())
                .containsExactly(live.result().id());
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
        assertThat(standing.liveRuns())
                .extracting(view -> view.result().id())
                .containsExactly(newer.result().id());
    }

    @Test
    void shouldNotLetAnOutrankedResultHoldOrBreakTheClaim() {
        add(2, ResultOutcome.NOT_MET);
        var live = data.getFirst();
        var late = ReviewTestData.view(
                live.result().itemId(),
                UUID.randomUUID(),
                now.minus(Duration.ofDays(9)),
                new ItemKind("scm.pull_request"),
                ResultOutcome.MET);
        data.add(ended(late, ResultEndReason.OUTRANKED, late.result().recordedAt()));

        var standing = only(service.getStandingSnapshots(1L, 7L, List.of(now)).getFirst());
        assertThat(standing.liveRuns())
                .extracting(view -> view.result().id())
                .containsExactly(live.result().id());
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
                        withoutPracticeVersion(migrated(view.result())),
                        view.practice(),
                        null,
                        view.review(),
                        view.item(),
                        ResultTestFixtures.fixedFreshness(Freshness.current(null)),
                        view.sealedFeedbackMode()));

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
        end(data.getFirst(), ResultEndReason.WORK_CHANGED, now.minusSeconds(1));
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

    /** Ends the result of {@code view}, which the history holds, as ResultClaimWriter does. */
    private void end(ResultView view, ResultEndReason reason, Instant at) {
        data.set(data.indexOf(view), ended(view, reason, at));
    }

    // Writes what ResultClaimWriter writes when a later review claims the same work: the prior claim ends at the
    // later recording.
    private ResultView replace(
            ResultView prior, long day, ResultOutcome outcome, ReviewOrigin origin, ResultEndReason reason) {
        var next = ReviewTestData.view(
                prior.result().itemId(),
                UUID.randomUUID(),
                now.minus(Duration.ofDays(10 - day)),
                new ItemKind("scm.pull_request"),
                outcome);
        next.review().setOrigin(origin);
        end(prior, reason, next.result().recordedAt());
        data.add(next);
        return next;
    }

    private static ResultView sealedIn(ResultView view, FeedbackMode mode) {
        return new ResultView(
                view.result(), view.practice(), view.version(), view.review(), view.item(), view.freshness(), mode);
    }

    // A result the cutover carried over from the earlier model: kept as recorded, without current proof.
    private void earlier(int index) {
        var view = data.get(index);
        data.set(
                index,
                new ResultView(
                        migrated(view.result()),
                        view.practice(),
                        view.version(),
                        view.review(),
                        view.item(),
                        ResultTestFixtures.fixedFreshness(Freshness.current(null)),
                        view.sealedFeedbackMode()));
    }

    private void withFreshness(UUID resultId, Freshness freshness) {
        var view = data.stream()
                .filter(candidate -> candidate.result().id().equals(resultId))
                .findFirst()
                .orElseThrow();
        data.set(
                data.indexOf(view),
                new ResultView(
                        view.result(),
                        view.practice(),
                        view.version(),
                        view.review(),
                        view.item(),
                        ResultTestFixtures.fixedFreshness(freshness),
                        view.sealedFeedbackMode()));
    }

    private PracticeStandingDTO card() {
        return service.getStandings(1L).getFirst();
    }

    private void add(long id, ResultOutcome outcome) {
        data.add(ReviewTestData.view(
                id, UUID.randomUUID(), now.minus(Duration.ofDays(10 - id)), new ItemKind("scm.pull_request"), outcome));
    }
}
