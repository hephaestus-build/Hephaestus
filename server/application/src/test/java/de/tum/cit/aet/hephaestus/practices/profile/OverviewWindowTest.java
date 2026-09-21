package de.tum.cit.aet.hephaestus.practices.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class OverviewWindowTest {

    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final Instant PREVIOUS_RUN = Instant.parse("2026-09-09T09:00:00Z");

    @Test
    @DisplayName("the window opens at the previous run and closes now")
    void shouldOpenAtThePreviousRun() {
        OverviewWindow window = OverviewWindow.sincePreviousRun(PREVIOUS_RUN, NOW);

        assertThat(window.since()).isEqualTo(PREVIOUS_RUN);
        assertThat(window.until()).isEqualTo(NOW);
        assertThat(window.contains(PREVIOUS_RUN))
                .as("the edge the window opens after")
                .isFalse();
        assertThat(window.contains(PREVIOUS_RUN.plusSeconds(1))).isTrue();
        assertThat(window.contains(NOW)).as("the edge the window closes at").isTrue();
    }

    @Test
    @DisplayName("with no previous run the window spans one look-back")
    void shouldSpanOneLookBackWhenThereIsNoPreviousRun() {
        OverviewWindow window = OverviewWindow.sincePreviousRun(null, NOW);

        assertThat(window.since()).isEqualTo(NOW.minus(OverviewWindow.LOOKBACK));
        assertThat(window.until()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a previous run older than the look-back is clamped to it, since no standing reaches further")
    void shouldClampToTheLookBackWhenThePreviousRunIsOlder() {
        Instant longAgo = NOW.minus(Duration.ofDays(200));

        assertThat(OverviewWindow.sincePreviousRun(longAgo, NOW).since()).isEqualTo(NOW.minus(OverviewWindow.LOOKBACK));
    }

    @Test
    @DisplayName(
            "a previous run recorded in the instant the profile is read cannot open a window, so the look-back does")
    void shouldFallBackToTheLookBackWhenThePreviousRunIsNow() {
        assertThat(OverviewWindow.sincePreviousRun(NOW, NOW).since()).isEqualTo(NOW.minus(OverviewWindow.LOOKBACK));
    }

    @Test
    void shouldRefuseEdgesWhenTheyAreOutOfOrder() {
        assertThatThrownBy(() -> new OverviewWindow(NOW, PREVIOUS_RUN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before");
        assertThatThrownBy(() -> new OverviewWindow(NOW, NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
