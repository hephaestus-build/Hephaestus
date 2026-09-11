package de.tum.cit.aet.hephaestus.practices.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OutcomeTest extends BaseUnitTest {
    @ParameterizedTest
    @CsvSource({"PRESENT,GOOD,POSITIVE", "ABSENT,GOOD,NEGATIVE", "PRESENT,BAD,NEGATIVE", "ABSENT,BAD,POSITIVE"})
    void derivesEveryMatrixCell(Presence presence, Assessment assessment, Outcome expected) {
        assertThat(Outcome.of(presence, assessment)).isEqualTo(expected);
    }

    @Test
    void unassessedHasNoOutcomeAndHalfAnAssessmentIsRejected() {
        assertThat(Outcome.of(null, null)).isNull();
        assertThatThrownBy(() -> Outcome.of(Presence.ABSENT, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Outcome.of(null, Assessment.BAD)).isInstanceOf(IllegalArgumentException.class);
    }
}
