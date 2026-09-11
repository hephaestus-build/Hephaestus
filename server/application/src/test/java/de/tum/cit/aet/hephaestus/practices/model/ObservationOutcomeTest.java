package de.tum.cit.aet.hephaestus.practices.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ObservationOutcomeTest {

    @Test
    @DisplayName("maps every cell of the presence × assessment matrix")
    void shouldMapEveryMatrixCell() {
        assertThat(ObservationOutcome.of(AssessmentStatus.ASSESSED, Presence.PRESENT, Assessment.GOOD))
                .isEqualTo(ObservationOutcome.DEMONSTRATED_STRENGTH);
        assertThat(ObservationOutcome.of(AssessmentStatus.ASSESSED, Presence.PRESENT, Assessment.BAD))
                .isEqualTo(ObservationOutcome.COMMISSION_PROBLEM);
        assertThat(ObservationOutcome.of(AssessmentStatus.ASSESSED, Presence.ABSENT, Assessment.GOOD))
                .isEqualTo(ObservationOutcome.SAFE_AVOIDANCE);
        assertThat(ObservationOutcome.of(AssessmentStatus.ASSESSED, Presence.ABSENT, Assessment.BAD))
                .isEqualTo(ObservationOutcome.OMISSION_GAP);
    }

    @Test
    @DisplayName("keeps unassessed statuses distinct")
    void shouldKeepUnassessedStatusesDistinct() {
        assertThat(ObservationOutcome.of(AssessmentStatus.NOT_APPLICABLE, null, null))
                .isEqualTo(ObservationOutcome.NOT_APPLICABLE);
        assertThat(ObservationOutcome.of(AssessmentStatus.UNDETERMINED, null, null))
                .isEqualTo(ObservationOutcome.UNDETERMINED);
    }

    @Test
    @DisplayName("rejects a pair the presence/assessment coherence CHECK would reject")
    void shouldRejectIncoherentPairs() {
        assertThatThrownBy(() -> ObservationOutcome.of(AssessmentStatus.ASSESSED, Presence.PRESENT, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObservationOutcome.of(AssessmentStatus.NOT_APPLICABLE, null, Assessment.GOOD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObservationOutcome.of(AssessmentStatus.UNDETERMINED, null, Assessment.BAD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sorts the four applicable outcomes into positive and negative evidence")
    void shouldSortOutcomesByValence() {
        assertThat(ObservationOutcome.DEMONSTRATED_STRENGTH.isPositive()).isTrue();
        assertThat(ObservationOutcome.SAFE_AVOIDANCE.isPositive()).isTrue();
        assertThat(ObservationOutcome.COMMISSION_PROBLEM.isNegative()).isTrue();
        assertThat(ObservationOutcome.OMISSION_GAP.isNegative()).isTrue();
        assertThat(ObservationOutcome.NOT_APPLICABLE.isPositive()).isFalse();
        assertThat(ObservationOutcome.NOT_APPLICABLE.isNegative()).isFalse();
        assertThat(ObservationOutcome.NOT_APPLICABLE.isApplicable()).isFalse();
        assertThat(ObservationOutcome.OMISSION_GAP.isApplicable()).isTrue();
    }

    @Test
    @DisplayName("denies a defect detector the demonstrated strength that would be its own defect")
    void shouldDenyDefectDetectorAnIncoherentStrength() {
        assertThat(ObservationOutcome.DEMONSTRATED_STRENGTH.isCoherentStrengthFor(true))
                .isFalse();
        assertThat(ObservationOutcome.SAFE_AVOIDANCE.isCoherentStrengthFor(true))
                .isTrue();

        assertThat(ObservationOutcome.DEMONSTRATED_STRENGTH.isCoherentStrengthFor(false))
                .isTrue();
        assertThat(ObservationOutcome.SAFE_AVOIDANCE.isCoherentStrengthFor(false))
                .isTrue();
    }
}
