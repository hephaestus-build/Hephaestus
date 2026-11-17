package de.tum.cit.aet.hephaestus.practices.result.trend;

import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.ended;
import static de.tum.cit.aet.hephaestus.practices.result.trend.TrendResults.judged;
import static de.tum.cit.aet.hephaestus.practices.result.trend.TrendResults.noVerdict;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.result.ResultView;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("unit")
class OpportunityBundlerTest {

    @Test
    void shouldKeepOnlyLatestRunWithinAnArtifact() {
        UUID older = UUID.randomUUID();
        UUID latest = UUID.randomUUID();

        var prior = judged(1, older, "2026-08-10T09:00:00Z", ResultOutcome.NOT_MET);
        prior = ended(prior, ResultEndReason.REPLACED, Instant.parse("2026-08-11T09:00:00Z"));
        OpportunityBundler.Bundles result = bundle(prior, judged(1, latest, "2026-08-11T09:00:00Z", ResultOutcome.MET));

        assertThat(result.current()).hasSize(1);
        assertThat(result.current().getFirst().outcomes().met()).isEqualTo(1);
        assertThat(result.current().getFirst().outcomes().notMet()).isZero();
    }

    @Test
    void shouldTreatBurstySameDayArtifactsAsSeparateOpportunities() {
        OpportunityBundler.Bundles result = bundle(
                judged(1, UUID.randomUUID(), "2026-08-11T09:00:00Z", ResultOutcome.MET),
                judged(2, UUID.randomUUID(), "2026-08-11T09:01:00Z", ResultOutcome.MET),
                judged(3, UUID.randomUUID(), "2026-08-11T09:02:00Z", ResultOutcome.NOT_MET));

        assertThat(result.current()).hasSize(3);
    }

    @Test
    void shouldNotTurnCalendarGapIntoABundleBoundary() {
        OpportunityBundler.Bundles result = bundle(
                judged(1, UUID.randomUUID(), "2026-07-01T09:00:00Z", ResultOutcome.NOT_MET),
                judged(2, UUID.randomUUID(), "2026-08-11T09:00:00Z", ResultOutcome.MET));

        assertThat(result.current()).hasSize(2);
        assertThat(result.previous()).isEmpty();
    }

    @Test
    void shouldUseStableArtifactOrderWhenTimestampsTie() {
        ResultView first = judged(1, UUID.randomUUID(), "2026-08-11T09:00:00Z", ResultOutcome.MET);
        ResultView second = judged(2, UUID.randomUUID(), "2026-08-11T09:00:00Z", ResultOutcome.NOT_MET);

        OpportunityBundler.Bundles forward =
                OpportunityBundler.bundle(List.of(first, second), Instant.parse("2026-05-01T00:00:00Z"), 1);
        OpportunityBundler.Bundles reversed =
                OpportunityBundler.bundle(List.of(second, first), Instant.parse("2026-05-01T00:00:00Z"), 1);

        assertThat(forward.current().getFirst().itemId()).isEqualTo(2);
        assertThat(reversed.current().getFirst().itemId()).isEqualTo(2);
    }

    @Test
    void shouldDiscardOpportunitiesOutsideTheHorizon() {
        OpportunityBundler.Bundles result = OpportunityBundler.bundle(
                List.of(
                        judged(1, UUID.randomUUID(), "2026-01-01T09:00:00Z", ResultOutcome.NOT_MET),
                        judged(2, UUID.randomUUID(), "2026-08-11T09:00:00Z", ResultOutcome.MET)),
                Instant.parse("2026-05-01T00:00:00Z"),
                4);

        assertThat(result.trail()).hasSize(1);
    }

    @Test
    void shouldReportHowManyMoreOpportunitiesEnableComparison() {
        OpportunityBundler.Bundles result = bundle(
                judged(1, UUID.randomUUID(), "2026-08-11T09:00:00Z", ResultOutcome.MET),
                judged(2, UUID.randomUUID(), "2026-08-11T10:00:00Z", ResultOutcome.MET),
                judged(3, UUID.randomUUID(), "2026-08-11T11:00:00Z", ResultOutcome.NOT_MET),
                judged(4, UUID.randomUUID(), "2026-08-11T12:00:00Z", ResultOutcome.NOT_MET),
                judged(5, UUID.randomUUID(), "2026-08-11T13:00:00Z", ResultOutcome.NOT_MET));

        assertThat(result.current()).hasSize(4);
        assertThat(result.previous()).hasSize(1);
        assertThat(result.opportunitiesUntilComparable(4, 3)).isEqualTo(2);
    }

    @ParameterizedTest(name = "{0} decided pieces of work leave {1} to go")
    @CsvSource({"0, 8", "2, 6", "4, 4", "6, 2", "8, 0"})
    void shouldCountWhatTheCurrentBundleLacksBeforeThePreviousOne(int decided, int missing) {
        ResultView[] practiceResults = new ResultView[decided];
        for (int index = 0; index < decided; index++) {
            practiceResults[index] =
                    judged(index + 1, UUID.randomUUID(), "2026-08-11T0" + index + ":00:00Z", ResultOutcome.MET);
        }

        assertThat(bundle(practiceResults).opportunitiesUntilComparable(4, 4)).isEqualTo(missing);
    }

    @Test
    void shouldKeepAnOpportunityThatProducedNoVerdictOutOfTheBundles() {
        // It stays in the trail: the practice looked at this piece of reviewed work, which is a different fact from
        // never
        // having reviewed it. But it carries no verdict, so it must not become a sample.
        OpportunityBundler.Bundles result = bundle(
                judged(1, UUID.randomUUID(), "2026-08-11T09:00:00Z", ResultOutcome.MET),
                noVerdict(2, UUID.randomUUID(), "2026-08-11T10:00:00Z"),
                judged(3, UUID.randomUUID(), "2026-08-11T11:00:00Z", ResultOutcome.NOT_MET));

        assertThat(result.trail()).hasSize(3);
        assertThat(result.current()).hasSize(2);
        assertThat(result.current())
                .noneMatch(opportunity -> opportunity.outcomes().doesNotApply() > 0);
    }

    @Test
    void shouldNotLetAVerdictlessRunSupersedeAnEarlierVerdictOnTheSameArtifact() {
        var prior = judged(1, UUID.randomUUID(), "2026-08-10T09:00:00Z", ResultOutcome.NOT_MET);
        prior = ended(prior, ResultEndReason.REPLACED, Instant.parse("2026-08-11T09:00:00Z"));
        OpportunityBundler.Bundles result = bundle(prior, noVerdict(1, UUID.randomUUID(), "2026-08-11T09:00:00Z"));

        assertThat(result.trail()).hasSize(1);
        assertThat(result.trail().getFirst().decided()).isFalse();
        assertThat(result.current()).isEmpty();
    }

    private static OpportunityBundler.Bundles bundle(ResultView... practiceResults) {
        return OpportunityBundler.bundle(List.of(practiceResults), Instant.parse("2026-05-01T00:00:00Z"), 4);
    }
}
