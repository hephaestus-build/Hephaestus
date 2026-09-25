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
class PracticeTrendCalculatorTest {

    private final TrendProperties properties = new TrendProperties();

    @Test
    void shouldDateAPracticeSpanFromComparedOpportunitiesOnly() {
        // The run in March looked and found nothing to judge. It belongs in the trail — the practice DID see
        // that piece of reviewed work — but "these comparisons span N days" must not count it, or a quiet stretch would
        // stretch the provenance without adding a comparison.
        PracticeTrend trend = PracticeTrendCalculator.calculatePractice(
                "testing",
                List.of(
                        noVerdict(7L, "2026-03-01T09:00:00Z"),
                        judged(40L, "2026-05-01T09:00:00Z", Assessment.BAD),
                        judged(55L, "2026-06-01T09:00:00Z", Assessment.GOOD)),
                Instant.parse("2026-01-01T00:00:00Z"),
                properties);

        assertThat(trend.support().firstOpportunityAt()).isEqualTo(Instant.parse("2026-05-01T09:00:00Z"));
        assertThat(trend.support().lastOpportunityAt()).isEqualTo(Instant.parse("2026-06-01T09:00:00Z"));
        assertThat(trend.support().calendarSpanDays()).isEqualTo(32);
        // Still visible in the trail, which is what the chart draws.
        assertThat(trend.opportunities()).hasSize(3);
    }

    @Test
    void shouldReportPracticeScopeWithoutPracticeCounts() {
        // The two practice counts are a group fact. At practice scope they are absent, and that absence
        // reaches the client — which is why the support factory has two entry points rather than one with
        // optional arguments.
        PracticeTrend trend = PracticeTrendCalculator.calculatePractice(
                "testing",
                List.of(judged(40L, "2026-05-01T09:00:00Z", Assessment.GOOD)),
                Instant.parse("2026-01-01T00:00:00Z"),
                properties);

        assertThat(trend.support().comparablePractices()).isNull();
        assertThat(trend.support().eligiblePractices()).isNull();
    }
}
