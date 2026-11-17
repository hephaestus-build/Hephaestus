package de.tum.cit.aet.hephaestus.practices.result.trend;

import static de.tum.cit.aet.hephaestus.practices.result.trend.TrendResults.clean;
import static de.tum.cit.aet.hephaestus.practices.result.trend.TrendResults.noVerdict;
import static de.tum.cit.aet.hephaestus.practices.result.trend.TrendResults.problem;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.result.ResultView;
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
        ResultView older = clean(3L, "2026-05-03T09:00:00Z");
        ResultView newest = clean(4L, "2026-05-04T09:00:00Z");
        PracticeTrend.CleanWork cleanWork = trend(
                        clean(1L, "2026-05-01T09:00:00Z"), problem(2L, "2026-05-02T09:00:00Z"), older, newest)
                .cleanWork();

        assertThat(cleanWork.count()).isEqualTo(2);
        assertThat(cleanWork.decidedWork()).isEqualTo(4);
        assertThat(cleanWork.since()).isEqualTo(Instant.parse("2026-05-03T09:00:00Z"));
        assertThat(cleanWork.kind()).isEqualTo(new ItemKind("scm.pull_request"));
        assertThat(cleanWork.reviewIds())
                .containsExactly(newest.result().reviewId(), older.result().reviewId());
    }

    @Test
    void shouldSkipAnOpportunityWhenItHasNoVerdict() {
        PracticeTrend.CleanWork cleanWork = trend(
                        clean(1L, "2026-05-01T09:00:00Z"),
                        noVerdict(2L, "2026-05-02T09:00:00Z"),
                        clean(3L, "2026-05-03T09:00:00Z"))
                .cleanWork();

        assertThat(cleanWork.count()).isEqualTo(2);
        assertThat(cleanWork.decidedWork()).isEqualTo(2);
        assertThat(cleanWork.since()).isEqualTo(Instant.parse("2026-05-01T09:00:00Z"));
    }

    @Test
    void shouldNameTheMajorityKindWhenTheCleanRunMixesKinds() {
        PracticeTrend.CleanWork cleanWork = trend(
                        clean(1L, "2026-05-01T09:00:00Z", new ItemKind("scm.issue")),
                        clean(2L, "2026-05-02T09:00:00Z", new ItemKind("scm.pull_request")),
                        clean(3L, "2026-05-03T09:00:00Z", new ItemKind("scm.pull_request")))
                .cleanWork();

        assertThat(cleanWork.count()).isEqualTo(3);
        assertThat(cleanWork.kind()).isEqualTo(new ItemKind("scm.pull_request"));
    }

    @Test
    void shouldHaveNoRunWhenTheNewestOpportunityRaisedAProblem() {
        PracticeTrend.CleanWork cleanWork = trend(
                        clean(1L, "2026-05-01T09:00:00Z"), problem(2L, "2026-05-02T09:00:00Z"))
                .cleanWork();

        assertThat(cleanWork.count()).isZero();
        assertThat(cleanWork.decidedWork()).isEqualTo(2);
        assertThat(cleanWork.kind()).isNull();
        assertThat(cleanWork.since()).isNull();
    }

    private PracticeTrend trend(ResultView... practiceResults) {
        return PracticeTrendCalculator.calculatePractice(List.of(practiceResults), CUTOFF, properties);
    }
}
