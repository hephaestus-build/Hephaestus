package de.tum.cit.aet.hephaestus.core.database.backup;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BackupScheduleMonitorTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final String FULL = "hephaestus-postgresql-backup-full";
    private static final String DIFF = "hephaestus-postgresql-backup-diff";

    private final Map<String, BackupSchedules.Schedule> schedules = new HashMap<>();
    private final MovingClock clock = new MovingClock(NOW);
    private @Nullable RuntimeException failure;
    private MeterRegistry registry;
    private BackupScheduleMonitor monitor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        BackupSchedules reader = name -> {
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(schedules.get(name));
        };
        monitor = new BackupScheduleMonitor(
                reader, new BackupScheduleProperties("hephaestus", List.of(FULL, DIFF)), clock, registry);
    }

    @Test
    void shouldReportInfiniteAgeWhenNothingWasReadYet() {
        assertThat(age()).isInfinite();
    }

    @Test
    void shouldCountFromTheNewestSuccessOfAnySchedule() {
        schedules.put(FULL, new BackupSchedules.Schedule(NOW.minusSeconds(900_000), NOW.minusSeconds(400_000)));
        schedules.put(DIFF, new BackupSchedules.Schedule(NOW.minusSeconds(900_000), NOW.minusSeconds(3_600)));

        monitor.sample();

        assertThat(age()).isEqualTo(3_600);
        assertThat(failures()).isZero();
    }

    @Test
    void shouldCountFromTheOldestCreationBeforeTheFirstSuccess() {
        schedules.put(FULL, new BackupSchedules.Schedule(NOW.minusSeconds(200_000), null));
        schedules.put(DIFF, new BackupSchedules.Schedule(NOW.minusSeconds(150_000), null));

        monitor.sample();

        // A first backup that never runs, such as a Job that waits Pending, grows old from the schedules' creation.
        assertThat(age()).isEqualTo(200_000);
    }

    @Test
    void shouldReportInfiniteAgeWhenAScheduleIsMissing() {
        schedules.put(FULL, new BackupSchedules.Schedule(NOW.minusSeconds(900_000), NOW.minusSeconds(3_600)));

        monitor.sample();

        assertThat(age()).isInfinite();
        assertThat(failures()).isEqualTo(1);
    }

    @Test
    void shouldReportInfiniteAgeAfterAFailedReadAndNeverKeepTheOlderValue() {
        schedules.put(FULL, new BackupSchedules.Schedule(NOW.minusSeconds(900_000), NOW.minusSeconds(3_600)));
        schedules.put(DIFF, new BackupSchedules.Schedule(NOW.minusSeconds(900_000), NOW.minusSeconds(3_600)));
        monitor.sample();
        assertThat(age()).isEqualTo(3_600);

        failure = new IllegalStateException("forbidden");
        monitor.sample();

        assertThat(age()).isInfinite();
        assertThat(failures()).isEqualTo(1);
    }

    @Test
    void shouldGrowBetweenReadsFromTheLastReadSuccess() {
        schedules.put(FULL, new BackupSchedules.Schedule(NOW.minusSeconds(900_000), NOW.minusSeconds(60)));
        schedules.put(DIFF, new BackupSchedules.Schedule(NOW.minusSeconds(900_000), null));
        monitor.sample();
        assertThat(age()).isEqualTo(60);

        // No read in between: the age still counts from the same success at every scrape.
        clock.advance(Duration.ofMinutes(4));

        assertThat(age()).isEqualTo(300);
    }

    private double age() {
        return registry.get(CoreMetrics.DATABASE_BACKUP_AGE).gauge().value();
    }

    private double failures() {
        return registry.get(CoreMetrics.DATABASE_BACKUP_SAMPLE_FAILURES)
                .counter()
                .count();
    }

    /** A clock that a test moves forward. */
    private static final class MovingClock extends Clock {

        private Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
