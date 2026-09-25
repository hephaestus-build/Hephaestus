package de.tum.cit.aet.hephaestus.practices.observation.trend;

import static de.tum.cit.aet.hephaestus.practices.observation.trend.TrendObservations.clean;
import static de.tum.cit.aet.hephaestus.practices.observation.trend.TrendObservations.noVerdict;
import static de.tum.cit.aet.hephaestus.practices.observation.trend.TrendObservations.problem;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PracticeTrendTest {

    private static final Instant CUTOFF = Instant.parse("2026-01-01T00:00:00Z");
    private final TrendProperties properties = new TrendProperties();

    @Test
    void shouldCountBackFromTheNewestWhenAProblemEndsTheCleanRun() {
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
    void shouldSkipAnOpportunityWhenItHasNoVerdict() {
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
    void shouldNameTheMajorityKindWhenTheCleanRunMixesKinds() {
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
}
