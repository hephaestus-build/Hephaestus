package de.tum.cit.aet.hephaestus.practices.observation.trend;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PracticeTrendTest {

    private static final Instant CUTOFF = Instant.parse("2026-01-01T00:00:00Z");
    private final TrendProperties properties = new TrendProperties();

    @Test
    void shouldCountTheCleanRunBackFromTheNewestAndStopAtAProblem() {
        Observation older = clean(3L, "2026-05-03T09:00:00Z");
        Observation newest = clean(4L, "2026-05-04T09:00:00Z");
        PracticeTrend.CleanWork cleanWork = trend(
                        clean(1L, "2026-05-01T09:00:00Z"), problem(2L, "2026-05-02T09:00:00Z"), older, newest)
                .cleanWork();

        assertThat(cleanWork.count()).isEqualTo(2);
        assertThat(cleanWork.applicableWork()).isEqualTo(4);
        assertThat(cleanWork.since()).isEqualTo(Instant.parse("2026-05-03T09:00:00Z"));
        assertThat(cleanWork.kind()).isEqualTo(ArtifactKinds.PULL_REQUEST);
        assertThat(cleanWork.jobIds()).containsExactly(newest.getAgentJobId(), older.getAgentJobId());
    }

    @Test
    void shouldSkipAnOpportunityWithoutAVerdict() {
        PracticeTrend.CleanWork cleanWork = trend(
                        clean(1L, "2026-05-01T09:00:00Z"),
                        noVerdict(2L, "2026-05-02T09:00:00Z"),
                        clean(3L, "2026-05-03T09:00:00Z"))
                .cleanWork();

        // The verdictless piece neither breaks the run nor counts: two clean, two applicable, since the older clean
        // one.
        assertThat(cleanWork.count()).isEqualTo(2);
        assertThat(cleanWork.applicableWork()).isEqualTo(2);
        assertThat(cleanWork.since()).isEqualTo(Instant.parse("2026-05-01T09:00:00Z"));
    }

    @Test
    void shouldNameTheKindMostOfTheRunWasReviewedOn() {
        PracticeTrend.CleanWork cleanWork = trend(
                        clean(1L, "2026-05-01T09:00:00Z", ArtifactKinds.ISSUE),
                        clean(2L, "2026-05-02T09:00:00Z", ArtifactKinds.PULL_REQUEST),
                        clean(3L, "2026-05-03T09:00:00Z", ArtifactKinds.PULL_REQUEST))
                .cleanWork();

        assertThat(cleanWork.count()).isEqualTo(3);
        assertThat(cleanWork.kind()).isEqualTo(ArtifactKinds.PULL_REQUEST);
    }

    @Test
    void shouldHaveNoRunWhenTheNewestOpportunityRaisedAProblem() {
        PracticeTrend.CleanWork cleanWork = trend(
                        clean(1L, "2026-05-01T09:00:00Z"), problem(2L, "2026-05-02T09:00:00Z"))
                .cleanWork();

        assertThat(cleanWork.count()).isZero();
        assertThat(cleanWork.applicableWork()).isEqualTo(2);
        assertThat(cleanWork.kind()).isNull();
        assertThat(cleanWork.since()).isNull();
    }

    private PracticeTrend trend(Observation... observations) {
        return PracticeTrendCalculator.calculatePractice("testing", List.of(observations), CUTOFF, properties);
    }

    private static Observation clean(long artifactId, String observedAt) {
        return clean(artifactId, observedAt, ArtifactKinds.PULL_REQUEST);
    }

    private static Observation clean(long artifactId, String observedAt, ArtifactKind kind) {
        return observation(artifactId, observedAt, kind, Presence.PRESENT, Assessment.GOOD);
    }

    private static Observation problem(long artifactId, String observedAt) {
        return observation(artifactId, observedAt, ArtifactKinds.PULL_REQUEST, Presence.ABSENT, Assessment.BAD);
    }

    private static Observation noVerdict(long artifactId, String observedAt) {
        return observation(artifactId, observedAt, ArtifactKinds.PULL_REQUEST, Presence.NOT_APPLICABLE, null);
    }

    private static Observation observation(
            long artifactId,
            String observedAt,
            ArtifactKind kind,
            Presence presence,
            @org.jspecify.annotations.Nullable Assessment assessment) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(UUID.randomUUID())
                .artifactKind(kind)
                .artifactId(artifactId)
                .presence(presence)
                .assessment(assessment)
                .observedAt(Instant.parse(observedAt))
                .build();
    }
}
