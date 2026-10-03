package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ReviewClaimCurrentnessTest {

    @Test
    void shouldNotRestoreAnIssueClaimJustBecauseItsPracticeIsStillCurrent() {
        assertThat(ReviewClaimCurrentness.of("same", "same", Instant.now())).isEqualTo(ReviewClaimCurrentness.STALE);
    }

    @Test
    void shouldNotTreatHistoricalBehaviorClaimsAsCurrentPracticeOutcomes() {
        assertThat(ReviewClaimCurrentness.of("v4:one", "v4:one")).isEqualTo(ReviewClaimCurrentness.UNVERIFIABLE);
        assertThat(ReviewClaimCurrentness.of("v4:one", "v5:one")).isEqualTo(ReviewClaimCurrentness.UNVERIFIABLE);
    }

    @Test
    void shouldDeriveCurrentnessFromReviewSemantics() {
        assertThat(ReviewClaimCurrentness.of("v5:one", "v5:one")).isEqualTo(ReviewClaimCurrentness.CURRENT);
        assertThat(ReviewClaimCurrentness.of("v5:old", "v5:one")).isEqualTo(ReviewClaimCurrentness.STALE);
        assertThat(ReviewClaimCurrentness.of(null, "v5:one")).isEqualTo(ReviewClaimCurrentness.UNVERIFIABLE);
    }
}
