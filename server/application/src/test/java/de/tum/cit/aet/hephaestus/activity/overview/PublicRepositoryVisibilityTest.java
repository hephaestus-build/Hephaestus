package de.tum.cit.aet.hephaestus.activity.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class PublicRepositoryVisibilityTest {

    @ParameterizedTest
    @ValueSource(longs = {1, 24, 48})
    void shouldUseConfiguredVisibilityBound(long hours) {
        var policy = new PublicRepositoryVisibility(Duration.ofHours(hours));
        assertThat(policy.maxAgeSeconds())
                .isEqualTo((double) Duration.ofHours(hours).toSeconds());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void shouldRejectAnUnboundedVisibilityPolicy(long hours) {
        assertThatThrownBy(() -> new PublicRepositoryVisibility(Duration.ofHours(hours)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
