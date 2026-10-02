package de.tum.cit.aet.hephaestus.practices.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OutcomeTest extends BaseUnitTest {
    @ParameterizedTest
    @EnumSource(Outcome.class)
    void shouldRequireSeverityExactlyWhenNotMet(Outcome outcome) {
        if (outcome == Outcome.NOT_MET) {
            assertThatThrownBy(() -> outcome.validate(null)).isInstanceOf(IllegalArgumentException.class);
            for (Severity severity : Severity.values()) {
                assertThatCode(() -> outcome.validate(severity)).doesNotThrowAnyException();
            }
        } else {
            assertThatCode(() -> outcome.validate(null)).doesNotThrowAnyException();
            for (Severity severity : Severity.values()) {
                assertThatThrownBy(() -> outcome.validate(severity)).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }
}
