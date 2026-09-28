package de.tum.cit.aet.hephaestus.core.time;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TimeBucketsTest extends BaseUnitTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Test
    void shouldSplitIntoDaysWhenTheRangeSpansAtMost31Days() {
        TimeBuckets buckets = TimeBuckets.of(range("2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"), ZoneOffset.UTC);

        assertThat(buckets.size()).isEqualTo(TimeBucketSize.DAY);
        assertThat(buckets.starts())
                .hasSize(31)
                .startsWith(Instant.parse("2026-01-01T00:00:00Z"))
                .endsWith(Instant.parse("2026-01-31T00:00:00Z"));
    }

    @Test
    void shouldSplitIntoWeeksFromMondayWhenTheRangeSpansMoreThan31Days() {
        // 2026-01-01 is a Thursday.
        TimeBuckets buckets = TimeBuckets.of(range("2026-01-01T00:00:00Z", "2026-02-02T00:00:00Z"), ZoneOffset.UTC);

        assertThat(buckets.size()).isEqualTo(TimeBucketSize.WEEK);
        assertThat(buckets.starts())
                .startsWith(Instant.parse("2025-12-29T00:00:00Z"), Instant.parse("2026-01-05T00:00:00Z"))
                .endsWith(Instant.parse("2026-01-26T00:00:00Z"));
    }

    @Test
    void shouldKeepWeeksWhenTheRangeSpansExactly184Days() {
        TimeBuckets buckets = TimeBuckets.of(range("2025-08-01T00:00:00Z", "2026-02-01T00:00:00Z"), ZoneOffset.UTC);

        assertThat(buckets.size()).isEqualTo(TimeBucketSize.WEEK);
    }

    @Test
    void shouldSplitIntoMonthsWhenTheRangeSpansMoreThan184Days() {
        TimeBuckets buckets = TimeBuckets.of(range("2025-07-31T00:00:00Z", "2026-02-01T00:00:00Z"), ZoneOffset.UTC);

        assertThat(buckets.size()).isEqualTo(TimeBucketSize.MONTH);
        assertThat(buckets.starts())
                .containsExactly(
                        Instant.parse("2025-07-01T00:00:00Z"),
                        Instant.parse("2025-08-01T00:00:00Z"),
                        Instant.parse("2025-09-01T00:00:00Z"),
                        Instant.parse("2025-10-01T00:00:00Z"),
                        Instant.parse("2025-11-01T00:00:00Z"),
                        Instant.parse("2025-12-01T00:00:00Z"),
                        Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void shouldStartEachDayAtLocalMidnightWhenTheClocksGoForward() {
        // Berlin moves its clocks forward on 2026-03-29, so that day is 23 hours long.
        TimeBuckets buckets = TimeBuckets.of(range("2026-03-28T12:00:00Z", "2026-03-30T12:00:00Z"), BERLIN);

        assertThat(buckets.starts())
                .containsExactly(
                        Instant.parse("2026-03-27T23:00:00Z"),
                        Instant.parse("2026-03-28T23:00:00Z"),
                        Instant.parse("2026-03-29T22:00:00Z"));
    }

    @Test
    void shouldStartEachDayAtLocalMidnightWhenTheClocksGoBack() {
        // Berlin moves its clocks back on 2026-10-25, so that day is 25 hours long.
        TimeBuckets buckets = TimeBuckets.of(range("2026-10-24T12:00:00Z", "2026-10-26T12:00:00Z"), BERLIN);

        assertThat(buckets.starts())
                .containsExactly(
                        Instant.parse("2026-10-23T22:00:00Z"),
                        Instant.parse("2026-10-24T22:00:00Z"),
                        Instant.parse("2026-10-25T23:00:00Z"));
    }

    @Test
    void shouldStartEachWeekAtLocalMidnightOnMondayWhenTheZoneIsNotUtc() {
        // Sunday 2026-03-01 23:30 UTC is already Monday 2026-03-02 in Berlin.
        TimeBuckets buckets = TimeBuckets.of(range("2026-03-01T23:30:00Z", "2026-04-15T00:00:00Z"), BERLIN);

        assertThat(buckets.size()).isEqualTo(TimeBucketSize.WEEK);
        assertThat(buckets.starts())
                .startsWith(Instant.parse("2026-03-01T23:00:00Z"), Instant.parse("2026-03-08T23:00:00Z"))
                .as("Monday midnight after the clocks go forward on 29 March")
                .contains(Instant.parse("2026-03-29T22:00:00Z"));
    }

    @Test
    void shouldHaveNoBucketsWhenTheRangeIsEmpty() {
        TimeBuckets buckets = TimeBuckets.of(range("2026-03-04T00:00:00Z", "2026-03-04T00:00:00Z"), ZoneOffset.UTC);

        assertThat(buckets.starts()).isEmpty();
    }

    private static TimeRange range(String from, String to) {
        return new TimeRange(Instant.parse(from), Instant.parse(to));
    }
}
