package de.tum.cit.aet.hephaestus.integration.core.framework;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The cadence behind every staleness judgement: without it, "last synced 4h ago" cannot be called good
 * or bad.
 */
class CronSchedulesTest extends BaseUnitTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** A Wednesday noon in the scheduler's zone. */
    private static final Clock WEDNESDAY = at(LocalDateTime.parse("2026-06-10T12:00"));

    private static Clock at(LocalDateTime scheduledTime) {
        return Clock.fixed(scheduledTime.atZone(ZoneId.systemDefault()).toInstant(), ZoneOffset.UTC);
    }

    @Test
    void interval_dailyCron_isTwentyFourHours() {
        assertThat(CronSchedules.interval("0 0 3 * * *", WEDNESDAY)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void interval_hourlyCron_isOneHour() {
        assertThat(CronSchedules.interval("0 0 * * * *", WEDNESDAY)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void interval_everySixHoursCron_isSixHours() {
        // The Outline default — worth pinning, since its provider passes this literal through.
        assertThat(CronSchedules.interval("0 0 */6 * * *", WEDNESDAY)).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void interval_unparseableCron_isNullSoCallersDeclineToJudge() {
        // Not a default: a fabricated cadence would silently make every staleness verdict wrong.
        assertThat(CronSchedules.interval("not a cron", WEDNESDAY)).isNull();
    }

    @Test
    void interval_isMeasuredNotDeclared_soAnIrregularScheduleYieldsTheGapThatFollowsNow() {
        // Weekdays only: on Wednesday the next gap is a day, on Thursday it spans the weekend.
        assertThat(CronSchedules.interval("0 0 3 * * MON-FRI", WEDNESDAY)).isEqualTo(Duration.ofHours(24));
        assertThat(CronSchedules.interval("0 0 3 * * MON-FRI", at(LocalDateTime.parse("2026-06-11T12:00"))))
                .isEqualTo(Duration.ofDays(3));
    }

    @Test
    void interval_isWallClockTime_soAScheduleInTheRepeatedHourKeepsItsDailyCadence() {
        // On 2026-10-25 Berlin's 02:00 happens twice, an hour apart.
        assertThat(CronSchedules.interval(
                        "0 0 2 * * *", LocalDateTime.parse("2026-10-25T01:30").atZone(BERLIN)))
                .isEqualTo(Duration.ofHours(24));
    }

    @Test
    void nextRun_unparseableCron_isNull() {
        assertThat(CronSchedules.nextRun("not a cron", WEDNESDAY)).isNull();
    }

    @Test
    void nextRun_validCron_isTheCronsNextFireTimeAfterTheClock() {
        assertThat(CronSchedules.nextRun("0 0 3 * * *", WEDNESDAY))
                .isEqualTo(LocalDateTime.parse("2026-06-11T03:00")
                        .atZone(ZoneId.systemDefault())
                        .toInstant());
    }

    @ParameterizedTest(name = "{0} in Berlin runs next at {1}")
    @CsvSource({
        // The 25 h day: 24 h 26 min until the next midnight.
        "2026-10-25T00:34, 2026-10-25T23:00:00Z",
        // The 23 h day: 22 h 26 min until the next midnight.
        "2026-03-29T00:34, 2026-03-29T22:00:00Z",
    })
    void nextRun_onADaylightSavingDay_isTheNextWallClockMidnight(LocalDateTime now, Instant midnight) {
        assertThat(CronSchedules.nextRun("0 0 0 * * *", now.atZone(BERLIN))).isEqualTo(midnight);
    }

    @Test
    void nextRun_inTheRepeatedHour_isAfterNowNotOnTheFirstPass() {
        // On 2026-10-25 Berlin's 02:00–03:00 happens twice; at 02:10 on the second pass, the 02:30 run is still ahead.
        ZonedDateTime secondPass =
                LocalDateTime.parse("2026-10-25T02:10").atZone(BERLIN).withLaterOffsetAtOverlap();

        assertThat(CronSchedules.nextRun("0 30 2 * * *", secondPass)).isEqualTo(Instant.parse("2026-10-25T01:30:00Z"));
    }
}
