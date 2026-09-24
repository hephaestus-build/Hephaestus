package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ReviewClaimCurrentnessTest {

    @Test
    void shouldNotRestoreAnIssueClaimJustBecauseItsPracticeIsStillCurrent() {
        assertThat(ReviewClaimCurrentness.of("same", "same", java.time.Instant.now()))
                .isEqualTo(ReviewClaimCurrentness.STALE);
    }

    @Test
    void shouldDeriveCurrentnessFromDetectionSemantics() {
        assertThat(ReviewClaimCurrentness.of("v2:one", "v2:one")).isEqualTo(ReviewClaimCurrentness.CURRENT);
        assertThat(ReviewClaimCurrentness.of("v1:one", "v2:one")).isEqualTo(ReviewClaimCurrentness.STALE);
        assertThat(ReviewClaimCurrentness.of(null, "v2:one")).isEqualTo(ReviewClaimCurrentness.UNVERIFIABLE);
    }
}
