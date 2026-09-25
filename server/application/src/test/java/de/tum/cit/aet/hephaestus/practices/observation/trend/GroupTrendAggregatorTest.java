package de.tum.cit.aet.hephaestus.practices.observation.trend;

import static de.tum.cit.aet.hephaestus.practices.observation.trend.TrendObservations.judged;
import static de.tum.cit.aet.hephaestus.practices.observation.trend.TrendObservations.noVerdict;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class GroupTrendAggregatorTest {

    private final TrendProperties properties = new TrendProperties();

    @Test
    void shouldPreventThinEvidenceFromDominatingWellEvidencedPractice() {
        PracticeTrend wellEvidenced =
                trend("well", BetaPosterior.from(24, 21).differenceFrom(BetaPosterior.from(24, 3)));
        PracticeTrend thin = trend("thin", BetaPosterior.from(3, 0).differenceFrom(BetaPosterior.from(3, 3)));

        PracticeTrend group = GroupTrendAggregator.aggregate(
                "quality", List.of("well", "thin"), List.of(wellEvidenced, thin), properties);

        assertThat(group.direction()).isNotEqualTo(TrendDirection.DECLINING);
    }

    @Test
    void shouldDateAnGroupSpanFromComparedOpportunitiesOnly() {
        // The merged trail carries every practice's opportunities by artifact, so a verdictless one from a
        // sibling practice could otherwise date the whole group.
        PracticeTrend looked = PracticeTrendCalculator.calculatePractice(
                "naming",
                List.of(noVerdict(7L, "2026-03-01T09:00:00Z")),
                Instant.parse("2026-01-01T00:00:00Z"),
                properties);
        PracticeTrend judged = PracticeTrendCalculator.calculatePractice(
                "testing",
                List.of(
                        judged(40L, "2026-05-01T09:00:00Z", Assessment.BAD),
                        judged(55L, "2026-06-01T09:00:00Z", Assessment.GOOD)),
                Instant.parse("2026-01-01T00:00:00Z"),
                properties);

        PracticeTrend group = GroupTrendAggregator.aggregate(
                "quality", List.of("naming", "testing"), List.of(looked, judged), properties);

        assertThat(group.support().firstOpportunityAt()).isEqualTo(Instant.parse("2026-05-01T09:00:00Z"));
        assertThat(group.support().calendarSpanDays()).isEqualTo(32);
    }

    @Test
    void shouldMergeOneArtifactSeveralPracticesSawIntoOnePoint() {
        // Two practices reviewing the same pull request is one piece of reviewed work to a reader. Merging them by
        // artifact
        // is what stops a group's chart from drawing the same PR once per practice — and what stops the
        // opportunity counts from inflating with the number of practices rather than the amount of work.
        PracticeTrend naming = PracticeTrendCalculator.calculatePractice(
                "naming",
                List.of(judged(40L, "2026-05-01T09:00:00Z", Assessment.GOOD)),
                Instant.parse("2026-01-01T00:00:00Z"),
                properties);
        PracticeTrend testing = PracticeTrendCalculator.calculatePractice(
                "testing",
                List.of(judged(40L, "2026-05-01T10:00:00Z", Assessment.BAD)),
                Instant.parse("2026-01-01T00:00:00Z"),
                properties);

        PracticeTrend group = GroupTrendAggregator.aggregate(
                "quality", List.of("naming", "testing"), List.of(naming, testing), properties);

        assertThat(group.opportunities()).hasSize(1);
        assertThat(group.opportunities().getFirst().outcomes().applicable()).isEqualTo(2);
    }

    @Test
    void shouldCountOneArtifactOnceWhenPracticesPutItInDifferentBundles() {
        // Pull request 100 is current evidence for naming and previous evidence for testing, which saw four
        // newer pieces of work. Adding the two bundle counts would tell the reader the group rests on 16
        // pieces of reviewed work when it rests on 15.
        PracticeTrend naming = PracticeTrendCalculator.calculatePractice(
                "naming",
                List.of(
                        judged(100L, "2026-06-10T09:00:00Z", Assessment.GOOD),
                        judged(101L, "2026-06-09T09:00:00Z", Assessment.GOOD),
                        judged(102L, "2026-06-08T09:00:00Z", Assessment.GOOD),
                        judged(103L, "2026-06-07T09:00:00Z", Assessment.GOOD),
                        judged(104L, "2026-06-06T09:00:00Z", Assessment.BAD),
                        judged(105L, "2026-06-05T09:00:00Z", Assessment.BAD),
                        judged(106L, "2026-06-04T09:00:00Z", Assessment.BAD),
                        judged(107L, "2026-06-03T09:00:00Z", Assessment.BAD)),
                Instant.parse("2026-01-01T00:00:00Z"),
                properties);
        PracticeTrend testing = PracticeTrendCalculator.calculatePractice(
                "testing",
                List.of(
                        judged(200L, "2026-06-14T09:00:00Z", Assessment.GOOD),
                        judged(201L, "2026-06-13T09:00:00Z", Assessment.GOOD),
                        judged(202L, "2026-06-12T09:00:00Z", Assessment.GOOD),
                        judged(203L, "2026-06-11T09:00:00Z", Assessment.GOOD),
                        judged(100L, "2026-06-10T09:00:00Z", Assessment.BAD),
                        judged(204L, "2026-06-09T09:00:00Z", Assessment.BAD),
                        judged(205L, "2026-06-08T09:00:00Z", Assessment.BAD),
                        judged(206L, "2026-06-07T09:00:00Z", Assessment.BAD)),
                Instant.parse("2026-01-01T00:00:00Z"),
                properties);

        PracticeTrend group = GroupTrendAggregator.aggregate(
                "quality", List.of("naming", "testing"), List.of(naming, testing), properties);

        assertThat(group.support().opportunities()).isEqualTo(15);
    }

    private PracticeTrend trend(String slug, BetaPosterior.Difference difference) {
        TrendSupport support = new TrendSupport(
                4,
                4,
                8,
                0,
                null,
                null,
                null,
                null,
                null,
                properties.getBundleSize(),
                properties.getRopeHalfWidth(),
                properties.getCredibilityThreshold());
        return new PracticeTrend(
                slug,
                TrendScope.PRACTICE,
                TrendDirection.UNCERTAIN,
                support,
                OutcomeVector.EMPTY,
                OutcomeVector.EMPTY,
                List.of(),
                difference);
    }
}
