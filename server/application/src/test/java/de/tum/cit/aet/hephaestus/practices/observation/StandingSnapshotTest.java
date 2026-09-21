package de.tum.cit.aet.hephaestus.practices.observation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService.StandingSnapshot;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.trend.PracticeTrend;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class StandingSnapshotTest {

    private final StandingSnapshot snapshot = new StandingSnapshot(Map.of(), Map.of());

    @Test
    void shouldHoldWhenEveryOpportunityInTheStandingWindowIsClean() {
        assertThat(snapshot.isHolding(standing(PracticeStandingDTO.Standing.STRENGTH), cleanWork(4, 9)))
                .isTrue();
    }

    @Test
    void shouldNotHoldWhenAnOpportunityInTheWindowRaisedAProblem() {
        // Three clean of four in the window: a strength carried by weight, not a held one.
        assertThat(snapshot.isHolding(standing(PracticeStandingDTO.Standing.STRENGTH), cleanWork(3, 4)))
                .isFalse();
    }

    @Test
    void shouldHoldOverFewerApplicableOccasionsThanTheWindow() {
        // Two pieces of work ever judged, both clean: the bar is what there is, not the window's size.
        assertThat(snapshot.isHolding(standing(PracticeStandingDTO.Standing.STRENGTH), cleanWork(2, 2)))
                .isTrue();
    }

    @Test
    void shouldNotHoldBelowStrength() {
        assertThat(snapshot.isHolding(standing(PracticeStandingDTO.Standing.MIXED), cleanWork(4, 4)))
                .isFalse();
    }

    private static PracticeStandingDTO standing(PracticeStandingDTO.Standing standing) {
        return new PracticeStandingDTO(
                "testing", "Testing", null, null, null, null, standing, List.of(), List.of(), null, null);
    }

    private static PracticeTrend.CleanWork cleanWork(int count, int applicableWork) {
        return new PracticeTrend.CleanWork(
                count, applicableWork, ArtifactKinds.PULL_REQUEST, Instant.parse("2026-05-01T09:00:00Z"), List.of());
    }
}
