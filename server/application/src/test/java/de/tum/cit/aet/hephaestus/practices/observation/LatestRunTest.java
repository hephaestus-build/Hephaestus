package de.tum.cit.aet.hephaestus.practices.observation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LatestRunTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    @Test
    void shouldKeepOnlyEachPieceOfWorksNewestRunWhenAWorkWasReviewedTwice() {
        UUID earlier = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        Observation slipped = observation(7L, earlier, NOW.minus(Duration.ofDays(1)), Outcome.NOT_MET);
        Observation recovered = observation(7L, later, NOW, Outcome.MET);
        Observation alsoRecovered = observation(7L, later, NOW, Outcome.MET);
        Observation other = observation(8L, earlier, NOW.minus(Duration.ofDays(1)), Outcome.NOT_MET);

        assertThat(LatestRun.perWork(List.of(recovered, alsoRecovered, other, slipped)))
                .containsExactly(recovered, alsoRecovered, other);
    }

    @Test
    void shouldNameTheNewestRunWhenAWorkWasReviewedTwice() {
        UUID earlier = UUID.randomUUID();
        UUID later = UUID.randomUUID();

        assertThat(LatestRun.of(List.of(
                        observation(7L, earlier, NOW.minus(Duration.ofDays(1)), Outcome.NOT_MET),
                        observation(7L, later, NOW, Outcome.MET))))
                .isEqualTo(later);
    }

    /**
     * The standing reads a whole window at once: a run that re-reviewed one practice on a pull request does not
     * speak for the other practices it never looked at, and a campaign's reading does not erase the live one.
     */
    @Test
    void shouldKeepEachClaimsNewestRunWhenTheWindowSpansPracticesAndOrigins() {
        UUID earlier = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        Practice sizing = practice("reviewable-diff-size");
        Practice describing = practice("describe-what-and-why");
        Observation sizeSlipped = observation(7L, earlier, NOW.minus(Duration.ofDays(1)), Outcome.NOT_MET, sizing);
        Observation sizeRecovered = observation(7L, later, NOW, Outcome.MET, sizing);
        Observation described = observation(7L, earlier, NOW.minus(Duration.ofDays(1)), Outcome.MET, describing);
        Observation backfilled = Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(campaign)
                .practice(sizing)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(7L)
                .outcome(Outcome.NOT_MET)
                .origin(ObservationOrigin.BACKFILL)
                .observedAt(NOW.plus(Duration.ofDays(1)))
                .build();

        assertThat(LatestRun.perClaim(List.of(backfilled, sizeRecovered, described, sizeSlipped)))
                .containsExactly(backfilled, sizeRecovered, described);
    }

    @Test
    void shouldBreakATimestampTieOnTheJobIdWhenTwoRunsShareATimestamp() {
        UUID smaller = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID larger = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertThat(LatestRun.of(List.of(
                        observation(1L, larger, NOW, Outcome.NOT_MET), observation(1L, smaller, NOW, Outcome.MET))))
                .isEqualTo(larger);
    }

    /**
     * The same tie is broken in SQL by {@code ORDER BY agent_job_id DESC} over PostgreSQL's byte-wise
     * {@code uuid} ordering, under which {@code ff…} is the larger id; {@link UUID#compareTo} would call it
     * the smaller one, since it compares the two halves as signed longs.
     */
    @Test
    void shouldBreakATimestampTieTheWayPostgresOrdersUuidsWhenTheHighBitDiffers() {
        UUID highBitSet = UUID.fromString("ff000000-0000-0000-0000-000000000000");
        UUID highBitClear = UUID.fromString("0f000000-0000-0000-0000-000000000000");

        assertThat(LatestRun.of(List.of(
                        observation(1L, highBitClear, NOW, Outcome.MET),
                        observation(1L, highBitSet, NOW, Outcome.NOT_MET))))
                .isEqualTo(highBitSet);
    }

    private static Observation observation(long artifactId, UUID run, Instant observedAt, Outcome outcome) {
        return observation(artifactId, run, observedAt, outcome, practice("reviewable-diff-size"));
    }

    private static Observation observation(
            long artifactId, UUID run, Instant observedAt, Outcome outcome, Practice practice) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(run)
                .practice(practice)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(artifactId)
                .outcome(outcome)
                .observedAt(observedAt)
                .build();
    }

    private static Practice practice(String slug) {
        Practice practice = new Practice();
        practice.setSlug(slug);
        return practice;
    }
}
