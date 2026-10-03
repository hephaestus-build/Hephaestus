package de.tum.cit.aet.hephaestus.integration.core.framework;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The cadence behind every staleness judgement: without it, "last synced 4h ago" cannot be called good
 * or bad.
 */
class CronSchedulesTest extends BaseUnitTest {

    @Test
    void interval_dailyCron_isTwentyFourHours() {
        assertThat(CronSchedules.interval("0 0 3 * * *")).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void interval_hourlyCron_isOneHour() {
        assertThat(CronSchedules.interval("0 0 * * * *")).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void interval_everySixHoursCron_isSixHours() {
        // The Outline default — worth pinning, since its provider passes this literal through.
        assertThat(CronSchedules.interval("0 0 */6 * * *")).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void interval_unparseableCron_isNullSoCallersDeclineToJudge() {
        // Not a default: a fabricated cadence would silently make every staleness verdict wrong.
        assertThat(CronSchedules.interval("not a cron")).isNull();
    }

    @Test
    void interval_isMeasuredNotDeclared_soAnIrregularScheduleStillYieldsTheGapThatFollowsNow() {
        // Weekdays only: the gap after "now" is either 24h (Mon–Thu) or 72h (Fri). Both are real gaps in
        // this schedule, so the contract is only that a plausible one comes back — never null, never zero.
        Duration interval = CronSchedules.interval("0 0 3 * * MON-FRI");

        assertThat(interval).isNotNull();
        assertThat(interval).isBetween(Duration.ofHours(24), Duration.ofHours(72));
    }

    @Test
    void nextRun_unparseableCron_isNull() {
        assertThat(CronSchedules.nextRun("not a cron")).isNull();
    }

    @Test
    void nextRun_validCron_isInTheFuture() {
        assertThat(CronSchedules.nextRun("0 0 3 * * *")).isAfter(Instant.now());
    }
}
