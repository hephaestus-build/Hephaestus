package de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WeeklyDigestComposerTest extends BaseUnitTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @ParameterizedTest
    @CsvSource({
        // Monday 12 October 2026 in Berlin is UTC+2.
        "2026-10-12T07:00:00Z, true",
        "2026-10-12T06:59:59Z, false",
        "2026-10-12T18:59:59Z, true",
        "2026-10-12T19:00:00Z, false",
        // Monday 01:30 in Berlin is before nine; Tuesday is not Monday.
        "2026-10-11T23:30:00Z, false",
        "2026-10-13T07:00:00Z, false",
    })
    void shouldBeDueOnlyOnMondayFromNineToNineInTheEveningLocalTimeWhenTheJobRuns(Instant now, boolean due) {
        assertThat(WeeklyDigestComposer.isDue(now, BERLIN)).isEqualTo(due);
    }

    @Test
    void shouldBeDueInTheDevelopersOwnZoneWhenItIsAnotherDayInUtc() {
        assertThat(WeeklyDigestComposer.isDue(Instant.parse("2026-10-11T23:30:00Z"), ZoneId.of("Asia/Tokyo")))
                .isFalse();
        assertThat(WeeklyDigestComposer.isDue(Instant.parse("2026-10-12T00:30:00Z"), ZoneId.of("Asia/Tokyo")))
                .isTrue();
        // Monday 20:00 in Los Angeles is Tuesday in UTC.
        assertThat(WeeklyDigestComposer.isDue(Instant.parse("2026-10-13T03:00:00Z"), ZoneId.of("America/Los_Angeles")))
                .isTrue();
    }
}
