package de.tum.cit.aet.hephaestus.practices.weeklydigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileChangeDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class WeeklyDigestWindowTest extends BaseUnitTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Test
    void shouldCoverThePreviousLocalWeekWhenItIsMondayMorning() {
        WeeklyDigestWindow week = WeeklyDigestWindow.previousWeek(Instant.parse("2026-10-12T07:00:00Z"), BERLIN);

        assertThat(week.weekStart()).isEqualTo(LocalDate.parse("2026-10-05"));
        assertThat(week.start()).isEqualTo(Instant.parse("2026-10-04T22:00:00Z"));
        assertThat(week.end()).isEqualTo(Instant.parse("2026-10-11T22:00:00Z"));
        assertThat(week.days()).isEqualTo("5–11 October");
        assertThat(week.daysWithYear()).isEqualTo("5–11 October 2026");
    }

    @Test
    void shouldTileWeeksWithoutAGapWhenTheClocksChange() {
        // Berlin leaves summer time on Sunday 25 October 2026: that week is an hour longer.
        WeeklyDigestWindow first = new WeeklyDigestWindow(LocalDate.parse("2026-10-19"), BERLIN);
        WeeklyDigestWindow next = new WeeklyDigestWindow(LocalDate.parse("2026-10-26"), BERLIN);

        assertThat(first.end()).isEqualTo(next.start());
        assertThat(first.overview().contains(first.end())).isTrue();
        assertThat(next.overview().contains(first.end())).isFalse();
    }

    @Test
    void shouldNameBothMonthsWhenTheWeekCrossesThem() {
        assertThat(new WeeklyDigestWindow(LocalDate.parse("2026-09-28"), BERLIN).days())
                .isEqualTo("28 September – 4 October");
        assertThat(new WeeklyDigestWindow(LocalDate.parse("2025-12-29"), BERLIN).daysWithYear())
                .isEqualTo("29 December 2025 – 4 January 2026");
    }

    @Test
    void shouldRefuseAWeekWhenItDoesNotStartOnMonday() {
        assertThatThrownBy(() -> new WeeklyDigestWindow(LocalDate.parse("2026-10-06"), BERLIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReadBackThePayloadAndNothingWhenItIsMalformed() {
        JsonMapper mapper = JsonMapper.builder().build();
        WeeklyDigestPayload payload = new WeeklyDigestPayload(
                new WeeklyDigestWindow(LocalDate.parse("2026-10-05"), BERLIN),
                List.of(UUID.randomUUID()),
                List.of(new WeeklyDigestChangeKey(ProfileChangeDTO.Type.GROUP_MOVED, null, "reviews")));

        JsonNode stored = payload.toJson(mapper);

        assertThat(WeeklyDigestPayload.fromJson(stored, mapper)).contains(payload);
        assertThat(WeeklyDigestPayload.fromJson(
                        ((ObjectNode) stored.deepCopy())
                                .set(
                                        "window",
                                        mapper.createObjectNode()
                                                .put("weekStart", "2026-10-06")
                                                .put("zone", "Europe/Berlin")),
                        mapper))
                .isEmpty();
        assertThat(WeeklyDigestPayload.fromJson(null, mapper)).isEmpty();
    }
}
