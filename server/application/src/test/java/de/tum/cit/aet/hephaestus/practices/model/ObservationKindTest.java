package de.tum.cit.aet.hephaestus.practices.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ObservationKindTest {

    @Test
    @DisplayName("maps every cell of the presence × assessment matrix")
    void shouldMapEveryMatrixCell() {
        assertThat(ObservationKind.of(AssessmentStatus.ASSESSED, Presence.PRESENT, Assessment.GOOD))
                .isEqualTo(ObservationKind.DEMONSTRATED_STRENGTH);
        assertThat(ObservationKind.of(AssessmentStatus.ASSESSED, Presence.PRESENT, Assessment.BAD))
                .isEqualTo(ObservationKind.COMMISSION_PROBLEM);
        assertThat(ObservationKind.of(AssessmentStatus.ASSESSED, Presence.ABSENT, Assessment.BAD))
                .isEqualTo(ObservationKind.SAFE_AVOIDANCE);
        assertThat(ObservationKind.of(AssessmentStatus.ASSESSED, Presence.ABSENT, Assessment.GOOD))
                .isEqualTo(ObservationKind.OMISSION_GAP);
    }

    @Test
    @DisplayName("keeps unassessed statuses distinct")
    void shouldKeepUnassessedStatusesDistinct() {
        assertThat(ObservationKind.of(AssessmentStatus.NOT_APPLICABLE, null, null))
                .isEqualTo(ObservationKind.NOT_APPLICABLE);
        assertThat(ObservationKind.of(AssessmentStatus.UNDETERMINED, null, null))
                .isEqualTo(ObservationKind.UNDETERMINED);
    }

    @Test
    @DisplayName("rejects a pair the presence/assessment coherence CHECK would reject")
    void shouldRejectIncoherentPairs() {
        assertThatThrownBy(() -> ObservationKind.of(AssessmentStatus.ASSESSED, Presence.PRESENT, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObservationKind.of(AssessmentStatus.NOT_APPLICABLE, null, Assessment.GOOD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObservationKind.of(AssessmentStatus.UNDETERMINED, null, Assessment.BAD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sorts the four applicable outcomes into positive and negative evidence")
    void shouldSortOutcomesByValence() {
        assertThat(ObservationKind.DEMONSTRATED_STRENGTH.isPositive()).isTrue();
        assertThat(ObservationKind.SAFE_AVOIDANCE.isPositive()).isTrue();
        assertThat(ObservationKind.COMMISSION_PROBLEM.isNegative()).isTrue();
        assertThat(ObservationKind.OMISSION_GAP.isNegative()).isTrue();
        assertThat(ObservationKind.NOT_APPLICABLE.isPositive()).isFalse();
        assertThat(ObservationKind.NOT_APPLICABLE.isNegative()).isFalse();
        assertThat(ObservationKind.NOT_APPLICABLE.isApplicable()).isFalse();
        assertThat(ObservationKind.OMISSION_GAP.isApplicable()).isTrue();
    }

    @Test
    @DisplayName("denies a defect detector the demonstrated strength that would be its own defect")
    void shouldDenyDefectDetectorAnIncoherentStrength() {
        assertThat(ObservationKind.DEMONSTRATED_STRENGTH.isCoherentStrengthFor(true))
                .isFalse();
        assertThat(ObservationKind.SAFE_AVOIDANCE.isCoherentStrengthFor(true)).isTrue();

        assertThat(ObservationKind.DEMONSTRATED_STRENGTH.isCoherentStrengthFor(false))
                .isTrue();
        assertThat(ObservationKind.SAFE_AVOIDANCE.isCoherentStrengthFor(false)).isTrue();
    }
}
