package de.tum.cit.aet.hephaestus.practices.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class ObservationCoherenceTest extends BaseUnitTest {
    @Test
    void shouldRequireAnOutcomeWhenPersisted() {
        assertThatThrownBy(() -> Observation.builder().build().onCreate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Outcome is required");
    }

    @Test
    void shouldKeepUndeterminedDistinctFromCaptureFailure() {
        var observation = Observation.builder().outcome(Outcome.UNDETERMINED).build();
        observation.onCreate();
        assertThat(observation.getOutcome()).isEqualTo(Outcome.UNDETERMINED);
        assertThat(observation.getSeverity()).isNull();
    }
}
