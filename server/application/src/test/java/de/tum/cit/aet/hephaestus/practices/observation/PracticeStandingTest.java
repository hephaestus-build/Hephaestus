package de.tum.cit.aet.hephaestus.practices.observation;

import static de.tum.cit.aet.hephaestus.practices.observation.trend.TrendObservations.clean;
import static de.tum.cit.aet.hephaestus.practices.observation.trend.TrendObservations.problem;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService.StandingSnapshot.PracticeStanding;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.trend.PracticeTrend;
import de.tum.cit.aet.hephaestus.practices.observation.trend.PracticeTrendService;
import de.tum.cit.aet.hephaestus.practices.observation.trend.TrendProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** When a strength reads as held: the standing's own window is the bar, over the trend it was read off. */
@Tag("unit")
class PracticeStandingTest {

    private static final String SLUG = "testing";
    private static final Instant NOW = Instant.parse("2026-05-01T09:00:00Z");

    @Test
    void shouldHoldWhenTheOnlySlipIsOlderThanTheStandingWindow() {
        // Four clean in the window of four; the slip before them is history the bar does not reach.
        assertThat(standing(PracticeStandingDTO.Standing.STRENGTH, trendOf(false, true, true, true, true))
                        .isHolding())
                .isTrue();
    }

    @Test
    void shouldNotHoldWhenAnOpportunityInTheWindowRaisedAProblem() {
        // Three clean of four in the window: a strength carried by weight, not a held one.
        assertThat(standing(PracticeStandingDTO.Standing.STRENGTH, trendOf(false, true, true, true))
                        .isHolding())
                .isFalse();
    }

    @Test
    void shouldHoldWhenFewerPiecesOfWorkThanTheWindowWereJudged() {
        // Two pieces of work ever judged, both clean: the bar is what there is, not the window's size.
        assertThat(standing(PracticeStandingDTO.Standing.STRENGTH, trendOf(true, true))
                        .isHolding())
                .isTrue();
    }

    @Test
    void shouldNotHoldWhenTheStandingIsBelowStrength() {
        assertThat(standing(PracticeStandingDTO.Standing.MIXED, trendOf(true, true, true, true))
                        .isHolding())
                .isFalse();
    }

    private static PracticeStanding standing(PracticeStandingDTO.Standing standing, PracticeTrend trend) {
        PracticeStandingDTO dto = new PracticeStandingDTO(
                SLUG, "Testing", null, null, null, null, standing, List.of(), List.of(), null, null);
        return new PracticeStanding(dto, List.of(), trend, null);
    }

    /** One review per piece of work, oldest first: {@code true} came back clean, {@code false} raised a problem. */
    private static PracticeTrend trendOf(boolean... cleanOldestFirst) {
        List<Observation> observations = IntStream.range(0, cleanOldestFirst.length)
                .mapToObj(index -> observation(index, cleanOldestFirst[index]))
                .toList();
        return new PracticeTrendService(new TrendProperties(), Clock.fixed(NOW, ZoneOffset.UTC))
                .calculatePractice(SLUG, observations);
    }

    private static Observation observation(int index, boolean isClean) {
        // Newest last, one day apart, well inside the trend horizon.
        String observedAt = NOW.minus(Duration.ofDays(30L - index)).toString();
        return isClean ? clean(index + 1L, observedAt) : problem(index + 1L, observedAt);
    }
}
