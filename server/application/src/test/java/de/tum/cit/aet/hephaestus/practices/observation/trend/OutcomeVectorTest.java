package de.tum.cit.aet.hephaestus.practices.observation.trend;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class OutcomeVectorTest {

    @Test
    void shouldMapAllPresenceAssessmentCellsToTheSharedUiContract() {
        assertThat(OutcomeVector.of(Outcome.MET)).isEqualTo(new OutcomeVector(1, 0, 0, 0));
        assertThat(OutcomeVector.of(Outcome.MET)).isEqualTo(new OutcomeVector(1, 0, 0, 0));
        assertThat(OutcomeVector.of(Outcome.NOT_MET)).isEqualTo(new OutcomeVector(0, 1, 0, 0));
        assertThat(OutcomeVector.of(Outcome.NOT_MET)).isEqualTo(new OutcomeVector(0, 1, 0, 0));
        assertThat(OutcomeVector.of(Outcome.NOT_APPLICABLE)).isEqualTo(new OutcomeVector(0, 0, 1, 0));
    }
}
