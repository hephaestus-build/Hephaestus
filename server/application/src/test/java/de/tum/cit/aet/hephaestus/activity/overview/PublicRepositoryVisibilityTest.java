package de.tum.cit.aet.hephaestus.activity.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class PublicRepositoryVisibilityTest {
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @ParameterizedTest
    @ValueSource(longs = {1, 24, 48})
    void shouldUseConfiguredVisibilityBound(long hours) {
        var policy = new PublicRepositoryVisibility(CLOCK, Duration.ofHours(hours));
        assertThat(policy.confirmedAfter()).isEqualTo(NOW.minus(Duration.ofHours(hours)));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void shouldRejectAnUnboundedVisibilityPolicy(long hours) {
        assertThatThrownBy(() -> new PublicRepositoryVisibility(CLOCK, Duration.ofHours(hours)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
