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

    /** A requested or backfilled run is quoted in place of the live run before it, but only the live run counts. */
    @Test
    void shouldCountOnlyTheNewestLiveRunWhenARequestedOrBackfilledRunFollowsIt() {
        Observation live = observation(7L, UUID.randomUUID(), NOW.minus(Duration.ofDays(1)), Outcome.NOT_MET);
        Observation requested = observation(UUID.randomUUID(), NOW, ObservationOrigin.MANUAL);
        Observation backfilled =
                observation(UUID.randomUUID(), NOW.plus(Duration.ofDays(1)), ObservationOrigin.BACKFILL);
        List<Observation> window = List.of(backfilled, requested, live);

        assertThat(LatestRun.perClaim(window)).containsExactly(backfilled, requested);
        assertThat(LatestRun.perLiveClaim(window)).containsExactly(live);
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

    @Test
    void shouldLetTheNewerProvedOccasionSpeakWhenAnOlderOneCompletesLater() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        Observation newerRun = occasion(newer, NOW.plus(Duration.ofMinutes(11)), NOW.plus(Duration.ofMinutes(36)));
        Observation olderRun = occasion(older, NOW, NOW.plus(Duration.ofMinutes(50)));

        assertThat(LatestRun.perClaim(List.of(olderRun, newerRun))).containsExactly(newerRun);
        assertThat(LatestRun.perLiveClaim(List.of(olderRun, newerRun))).containsExactly(newerRun);
        assertThat(olderRun.getObservedAt())
                .as("completion stays the completion instant")
                .isEqualTo(NOW.plus(Duration.ofMinutes(50)));
    }

    @Test
    void shouldOrderByCompletionWhenARunProvedNoOccasion() {
        UUID legacy = UUID.randomUUID();
        UUID proved = UUID.randomUUID();
        Observation legacyRun = observation(legacy, NOW.plus(Duration.ofMinutes(50)), ObservationOrigin.LIVE);
        Observation provedRun = occasion(proved, NOW.plus(Duration.ofMinutes(11)), NOW.plus(Duration.ofMinutes(36)));

        assertThat(LatestRun.perClaim(List.of(legacyRun, provedRun)))
                .as("an unproved run is placed at its completion, never at a guessed occasion")
                .containsExactly(legacyRun);
    }

    /**
     * A run about one person on a pull request does not speak for another person reviewed on it, nor does one
     * workspace's run speak for another's mirror of the same id; the work alone still narrows per work.
     */
    @Test
    void shouldKeepEachPersonsAndWorkspacesNewestRunOfTheSameWorkAndPractice() {
        Observation alice = claim(UUID.randomUUID(), 1L, 10L, NOW);
        Observation aliceLater = claim(UUID.randomUUID(), 1L, 10L, NOW.plus(Duration.ofMinutes(1)));
        Observation bob = claim(UUID.randomUUID(), 1L, 11L, NOW.minus(Duration.ofMinutes(1)));
        Observation elsewhere = claim(UUID.randomUUID(), 2L, 10L, NOW.minus(Duration.ofMinutes(2)));
        List<Observation> window = List.of(alice, aliceLater, bob, elsewhere);

        assertThat(LatestRun.perClaim(window)).containsExactly(aliceLater, bob, elsewhere);
        assertThat(LatestRun.perLiveClaim(window)).containsExactly(aliceLater, bob, elsewhere);
        assertThat(LatestRun.perWork(window)).containsExactly(aliceLater);
    }

    /** A manual run is quoted with the live claim it follows; it neither counts nor is hidden behind it. */
    @Test
    void shouldGroupAManualRunWithTheLiveClaimAndKeepABackfillApart() {
        Observation live = claim(UUID.randomUUID(), 1L, 10L, NOW);
        Observation manual = Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(UUID.randomUUID())
                .workspaceId(1L)
                .aboutUserId(10L)
                .practice(practice("reviewable-diff-size"))
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(7L)
                .outcome(Outcome.MET)
                .origin(ObservationOrigin.MANUAL)
                .observedAt(NOW.plus(Duration.ofMinutes(1)))
                .build();
        Observation backfill = Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(UUID.randomUUID())
                .workspaceId(1L)
                .aboutUserId(10L)
                .practice(practice("reviewable-diff-size"))
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(7L)
                .outcome(Outcome.NOT_MET)
                .origin(ObservationOrigin.BACKFILL)
                .observedAt(NOW.minus(Duration.ofMinutes(1)))
                .build();
        List<Observation> window = List.of(live, manual, backfill);

        assertThat(LatestRun.perClaim(window)).containsExactly(manual, backfill);
        assertThat(LatestRun.perLiveClaim(window)).containsExactly(live);
    }

    private static Observation claim(UUID run, long workspaceId, long aboutUserId, Instant observedAt) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(run)
                .workspaceId(workspaceId)
                .aboutUserId(aboutUserId)
                .practice(practice("reviewable-diff-size"))
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(7L)
                .outcome(Outcome.NOT_MET)
                .origin(ObservationOrigin.LIVE)
                .observedAt(observedAt)
                .build();
    }

    private static Observation occasion(UUID run, Instant occasionAt, Instant observedAt) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(run)
                .practice(practice("reviewable-diff-size"))
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(7L)
                .outcome(Outcome.MET)
                .origin(ObservationOrigin.LIVE)
                .observedAt(observedAt)
                .occasionAt(occasionAt)
                .build();
    }

    private static Observation observation(UUID run, Instant observedAt, ObservationOrigin origin) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(run)
                .practice(practice("reviewable-diff-size"))
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(7L)
                .outcome(Outcome.MET)
                .origin(origin)
                .observedAt(observedAt)
                .build();
    }

    private static Practice practice(String slug) {
        Practice practice = new Practice();
        practice.setSlug(slug);
        return practice;
    }
}
