package de.tum.cit.aet.hephaestus.practices.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class ObservationCoherenceTest extends BaseUnitTest {
    @Test
    void shouldAcceptExactlyTheValidAxisCombinations() {
        for (AssessmentStatus status : AssessmentStatus.values()) {
            for (Presence presence : new Presence[] {null, Presence.PRESENT, Presence.ABSENT}) {
                for (Assessment assessment : new Assessment[] {null, Assessment.GOOD, Assessment.BAD}) {
                    for (Severity severity :
                            new Severity[] {null, Severity.INFO, Severity.MINOR, Severity.MAJOR, Severity.CRITICAL}) {
                        var observation = Observation.builder()
                                .assessmentStatus(status)
                                .presence(presence)
                                .assessment(assessment)
                                .severity(severity)
                                .build();
                        boolean valid = (status == AssessmentStatus.ASSESSED
                                        ? presence != null && assessment != null
                                        : presence == null && assessment == null)
                                && (((presence == Presence.PRESENT && assessment == Assessment.BAD)
                                                || (presence == Presence.ABSENT && assessment == Assessment.GOOD))
                                        == (severity != null));
                        if (valid) assertThatCode(observation::onCreate).doesNotThrowAnyException();
                        else assertThatThrownBy(observation::onCreate).isInstanceOf(IllegalArgumentException.class);
                    }
                }
            }
        }
    }

    @Test
    void shouldRequireAnExplicitStatusAndRetainTheLiveOrigin() {
        assertThatThrownBy(() -> Observation.builder().build().onCreate()).isInstanceOf(IllegalStateException.class);
        var observation = Observation.builder()
                .assessmentStatus(AssessmentStatus.UNDETERMINED)
                .build();
        observation.onCreate();
        assertThat(observation.getOrigin()).isEqualTo(ObservationOrigin.LIVE);
        assertThat(Presence.values()).containsExactly(Presence.PRESENT, Presence.ABSENT);
    }
}
