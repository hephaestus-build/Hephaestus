package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ReviewPoolAdmissionTest {

    @Test
    void shouldCountTheLearnedLimitCappedByTheMaximumLessTheInteractiveReserve() {
        assertThat(ReviewPoolAdmission.room(8, null)).isEqualTo(6);
        assertThat(ReviewPoolAdmission.room(12, 8))
                .as("the maximum caps the learned limit")
                .isEqualTo(6);
        assertThat(ReviewPoolAdmission.room(2, null)).isEqualTo(1);
    }

    @Test
    void shouldLetAWideReviewThatRunsAloneMeasureInWavesOfThePoolsRoom() {
        // A review of 32 practices that an idle pool took alone: the learned limit of 8 holds six sessions.
        assertThat(ReviewPoolAdmission.sessionLimit(32, 6)).isEqualTo(6);
    }

    @Test
    void shouldLeaveTheRoomOfTheOtherRunningReviewsAndAlwaysAllowOneSession() {
        assertThat(ReviewPoolAdmission.sessionLimit(3, 3))
                .as("three sessions of another review leave three")
                .isEqualTo(3);
        assertThat(ReviewPoolAdmission.sessionLimit(3, -3))
                .as("a full pool still lets the review make progress")
                .isEqualTo(1);
        assertThat(ReviewPoolAdmission.sessionLimit(2, 6))
                .as("never more sessions than practices")
                .isEqualTo(2);
    }
}
