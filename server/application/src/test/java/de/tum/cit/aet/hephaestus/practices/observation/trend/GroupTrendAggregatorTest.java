package de.tum.cit.aet.hephaestus.practices.observation.trend;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
                List.of(inapplicable(7L, "2026-03-01T09:00:00Z")),
                Instant.parse("2026-01-01T00:00:00Z"),
                properties);
        PracticeTrend judged = PracticeTrendCalculator.calculatePractice(
                "testing",
                List.of(
                        observation(40L, "2026-05-01T09:00:00Z", Assessment.BAD),
                        observation(55L, "2026-06-01T09:00:00Z", Assessment.GOOD)),
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
                List.of(observation(40L, "2026-05-01T09:00:00Z", Assessment.GOOD)),
                Instant.parse("2026-01-01T00:00:00Z"),
                properties);
        PracticeTrend testing = PracticeTrendCalculator.calculatePractice(
                "testing",
                List.of(observation(40L, "2026-05-01T10:00:00Z", Assessment.BAD)),
                Instant.parse("2026-01-01T00:00:00Z"),
                properties);

        PracticeTrend group = GroupTrendAggregator.aggregate(
                "quality", List.of("naming", "testing"), List.of(naming, testing), properties);

        assertThat(group.opportunities()).hasSize(1);
        assertThat(group.opportunities().getFirst().outcomes().applicable()).isEqualTo(2);
    }

    private static Observation observation(long artifactId, String observedAt, Assessment assessment) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(UUID.randomUUID())
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(artifactId)
                .assessmentStatus(AssessmentStatus.ASSESSED)
                .presence(Presence.PRESENT)
                .assessment(assessment)
                .observedAt(Instant.parse(observedAt))
                .build();
    }

    private static Observation inapplicable(long artifactId, String observedAt) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(UUID.randomUUID())
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(artifactId)
                .assessmentStatus(AssessmentStatus.NOT_APPLICABLE)
                .presence(null)
                .observedAt(Instant.parse(observedAt))
                .build();
    }

    private PracticeTrend trend(String slug, BetaPosterior.Difference difference) {
        TrendSupport support = new TrendSupport(
                4,
                4,
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
