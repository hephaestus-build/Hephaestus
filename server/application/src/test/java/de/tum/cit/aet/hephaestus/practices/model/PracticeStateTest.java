package de.tum.cit.aet.hephaestus.practices.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class PracticeStateTest extends BaseUnitTest {

    @Test
    void shouldPutADraftInUseAndNeverMakeAPracticeInUseADraftAgain() {
        assertThat(PracticeState.DRAFT.canTransitionTo(PracticeState.ACTIVE)).isTrue();
        assertThat(PracticeState.ACTIVE.canTransitionTo(PracticeState.DRAFT)).isFalse();
        assertThat(PracticeState.ACTIVE.canTransitionTo(PracticeState.ACTIVE)).isFalse();
        assertThat(PracticeState.DRAFT.canTransitionTo(PracticeState.DRAFT)).isFalse();
    }

    @Test
    void shouldRefuseToActivateAPracticeThatIsAlreadyInUse() {
        var practice = new Practice();

        assertThatThrownBy(practice::activate).isInstanceOf(IllegalStateException.class);
        assertThat(practice.getState()).isEqualTo(PracticeState.ACTIVE);
    }
}
