package de.tum.cit.aet.hephaestus.activity.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class ActivityPeopleRangeParamsTest {
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @ParameterizedTest
    @CsvSource({"30d,30", "90d,90", "1y,365"})
    void shouldUsePresetWidthWhenRangeIsSelected(String preset, int days) {
        var range = new ActivityPeopleRangeParams(preset, null, null).resolve(CLOCK, NOW);
        assertThat(range.from()).isEqualTo(NOW.minus(Duration.ofDays(days)));
        assertThat(range.to()).isEqualTo(NOW);
    }

    @Test
    void shouldDefaultToNinetyDaysWhenRangeIsAbsent() {
        assertThat(new ActivityPeopleRangeParams(null, null, null)
                        .resolve(CLOCK, NOW)
                        .from())
                .isEqualTo(NOW.minus(Duration.ofDays(90)));
    }

    @Test
    void shouldUseDataStartWhenAllTimeIsSelected() {
        Instant start = NOW.minus(Duration.ofDays(180));
        assertThat(new ActivityPeopleRangeParams("all", null, null)
                        .resolve(CLOCK, start)
                        .from())
                .isEqualTo(start);
    }

    @Test
    void shouldReadFullHistoryWhenItSpansTenYears() {
        Instant start = NOW.minus(Duration.ofDays(3650));
        var range = new ActivityPeopleRangeParams("all", null, null).resolve(CLOCK, start);
        assertThat(range.from()).isEqualTo(start);
        assertThat(range.to()).isEqualTo(NOW);
    }

    @Test
    void shouldUseHalfOpenCustomBoundsWhenProvided() {
        Instant from = NOW.minusSeconds(60);
        var range = new ActivityPeopleRangeParams("custom", from, NOW).resolve(CLOCK, NOW);
        assertThat(range.from()).isEqualTo(from);
        assertThat(range.to()).isEqualTo(NOW);
    }

    @Test
    void shouldRejectConflictingBoundsWhenPresetAndStartAreProvided() {
        assertThatThrownBy(() -> new ActivityPeopleRangeParams("30d", NOW.minusSeconds(60), NOW).resolve(CLOCK, NOW))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void shouldRejectReversedBoundsWhenCustomRangeIsProvided() {
        assertThatThrownBy(() -> new ActivityPeopleRangeParams("custom", NOW, NOW.minusSeconds(1)).resolve(CLOCK, NOW))
                .isInstanceOf(ResponseStatusException.class);
    }
}
