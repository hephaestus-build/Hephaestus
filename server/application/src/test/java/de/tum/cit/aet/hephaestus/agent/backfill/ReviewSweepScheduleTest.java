package de.tum.cit.aet.hephaestus.agent.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The arithmetic a recurring sweep is made of: which days each run covers, and when the next one is.
 *
 * <p>Both are load-bearing rather than incidental. The window decides whether a sweep's observations may be
 * read beside reviews that events triggered; the advance decides whether a nightly sweep is still
 * nightly a month later.
 */
@DisplayName("Review sweep schedule")
class ReviewSweepScheduleTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-08-09T02:00:00Z");

    @Test
    void aSweepReachesBackExactlyItsLookback() {
        ReviewSweepSchedule schedule = schedule(ReviewSweepCadence.DAILY, 2);

        assertThat(schedule.windowStart(NOW)).isEqualTo(NOW.minus(Duration.ofDays(2)));
    }

    /**
     * Overlapping windows, not abutting ones: a paused or cancelled campaign leaves artifacts unswept, and
     * an abutting window would move past them for good. This costs nothing — an already-measured artifact
     * reproduces the key the first sweep recorded, and the ledger refuses the second offer.
     */
    @Test
    void consecutiveDailySweepsOverlapSoAMissedArtifactGetsAnotherTurn() {
        ReviewSweepSchedule schedule = schedule(ReviewSweepCadence.DAILY, 2);
        Instant tomorrow = NOW.plus(Duration.ofDays(1));

        assertThat(schedule.windowStart(tomorrow))
                .as("tomorrow's window still reaches back over today's")
                .isBefore(NOW);
    }

    /**
     * The window doesn't depend on when the sweep last ran, so a long outage can't turn "review everything
     * since we last looked" into a corpus chosen by hindsight.
     */
    @Test
    void anOutageDoesNotWidenTheWindowItComesBackTo() {
        ReviewSweepSchedule schedule = schedule(ReviewSweepCadence.DAILY, 2);
        schedule.setLastRunAt(NOW.minus(Duration.ofDays(30)));

        assertThat(schedule.windowStart(NOW)).isEqualTo(NOW.minus(Duration.ofDays(2)));
    }

    /** Derived from the previous occurrence, never from when the tick ran, so a delayed tick can't drift the phase. */
    @Test
    void theNextOccurrenceKeepsItsPhaseRatherThanDriftingByTheTicksDelay() {
        ReviewSweepSchedule schedule = schedule(ReviewSweepCadence.DAILY, 2);
        Instant due = Instant.parse("2026-08-09T02:00:00Z");
        schedule.setNextRunAt(due);

        // Eleven minutes late, as a tick that waited on the lock would be.
        schedule.advancePast(due.plus(Duration.ofMinutes(11)));

        assertThat(schedule.getNextRunAt()).isEqualTo(Instant.parse("2026-08-10T02:00:00Z"));
    }

    /** Missed occurrences are skipped, not queued — a week of downtime must not come back as seven priced campaigns. */
    @Test
    void aWeekOfDowntimeProducesOneSweepAndNotSeven() {
        ReviewSweepSchedule schedule = schedule(ReviewSweepCadence.DAILY, 2);
        Instant due = Instant.parse("2026-08-01T02:00:00Z");
        schedule.setNextRunAt(due);

        schedule.advancePast(Instant.parse("2026-08-08T09:13:00Z"));

        assertThat(schedule.getNextRunAt()).isEqualTo(Instant.parse("2026-08-09T02:00:00Z"));
    }

    @ParameterizedTest
    @EnumSource(ReviewSweepCadence.class)
    void anAdvanceAlwaysLandsStrictlyInTheFuture(ReviewSweepCadence cadence) {
        ReviewSweepSchedule schedule = schedule(cadence, 1);
        schedule.setNextRunAt(NOW.minus(Duration.ofDays(400)));

        schedule.advancePast(NOW);

        assertThat(schedule.getNextRunAt()).isAfter(NOW);
    }

    /** The jitter is a phase offset applied once, not a delay re-added on every advance. */
    @Test
    void twoWorkspacesCreatedTheSameSecondDoNotWakeTheSameMinuteForEver() {
        Instant firstA = ReviewSweepSchedule.firstRunAt(7L, NOW);
        Instant firstB = ReviewSweepSchedule.firstRunAt(23L, NOW);

        assertThat(firstA).isNotEqualTo(firstB);
        assertThat(Duration.between(NOW, firstA)).isLessThan(Duration.ofHours(1));
        assertThat(Duration.between(NOW, firstB)).isLessThan(Duration.ofHours(1));
    }

    @Test
    void theLookbackCeilingIsTwiceTheCadenceCappedAtAWeek() {
        assertThat(ReviewSweepCadence.DAILY.maxLookback()).isEqualTo(Duration.ofDays(2));
        assertThat(ReviewSweepCadence.WEEKLY.maxLookback()).isEqualTo(Duration.ofDays(7));
        assertThat(ReviewSweepCadence.WEEKLY.maxLookback()).isEqualTo(ReviewSweepCadence.MAX_LOOKBACK);
    }

    private static ReviewSweepSchedule schedule(ReviewSweepCadence cadence, int lookbackDays) {
        ReviewSweepSchedule schedule = new ReviewSweepSchedule();
        schedule.setArtifactKind(ArtifactKinds.PULL_REQUEST.value());
        schedule.setCadence(cadence);
        schedule.setLookbackDays(lookbackDays);
        schedule.setEnabled(true);
        schedule.setNextRunAt(NOW);
        return schedule;
    }
}
