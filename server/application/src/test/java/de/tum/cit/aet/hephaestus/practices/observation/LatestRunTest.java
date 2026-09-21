package de.tum.cit.aet.hephaestus.practices.observation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
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
    void keepsOnlyEachPieceOfWorksNewestRunInTheWindowsOrder() {
        UUID earlier = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        Observation slipped = observation(7L, earlier, NOW.minus(Duration.ofDays(1)), Assessment.BAD);
        Observation recovered = observation(7L, later, NOW, Assessment.GOOD);
        Observation alsoRecovered = observation(7L, later, NOW, Assessment.GOOD);
        Observation other = observation(8L, earlier, NOW.minus(Duration.ofDays(1)), Assessment.BAD);

        assertThat(LatestRun.perWork(List.of(recovered, alsoRecovered, other, slipped)))
                .containsExactly(recovered, alsoRecovered, other);
        assertThat(LatestRun.of(List.of(slipped, recovered))).isEqualTo(later);
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
        Observation sizeSlipped = observation(7L, earlier, NOW.minus(Duration.ofDays(1)), Assessment.BAD, sizing);
        Observation sizeRecovered = observation(7L, later, NOW, Assessment.GOOD, sizing);
        Observation described = observation(7L, earlier, NOW.minus(Duration.ofDays(1)), Assessment.GOOD, describing);
        Observation backfilled = Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(campaign)
                .practice(sizing)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(7L)
                .assessment(Assessment.BAD)
                .origin(ObservationOrigin.BACKFILL)
                .observedAt(NOW.plus(Duration.ofDays(1)))
                .build();

        assertThat(LatestRun.perClaim(List.of(backfilled, sizeRecovered, described, sizeSlipped)))
                .containsExactly(backfilled, sizeRecovered, described);
    }

    @Test
    void breaksATimestampTieOnTheJobId() {
        UUID smaller = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID larger = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertThat(LatestRun.of(List.of(
                        observation(1L, larger, NOW, Assessment.BAD), observation(1L, smaller, NOW, Assessment.GOOD))))
                .isEqualTo(larger);
    }

    private static Observation observation(long artifactId, UUID run, Instant observedAt, Assessment assessment) {
        return observation(artifactId, run, observedAt, assessment, practice("reviewable-diff-size"));
    }

    private static Observation observation(
            long artifactId, UUID run, Instant observedAt, Assessment assessment, Practice practice) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(run)
                .practice(practice)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(artifactId)
                .assessment(assessment)
                .observedAt(observedAt)
                .build();
    }

    private static Practice practice(String slug) {
        Practice practice = new Practice();
        practice.setSlug(slug);
        return practice;
    }
}
