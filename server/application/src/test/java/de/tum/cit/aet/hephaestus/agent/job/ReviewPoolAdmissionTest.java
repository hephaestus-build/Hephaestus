package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ReviewPoolAdmissionTest {

    private static ReviewJobRepository.PoolLoad load(long practices, @Nullable Integer learned, @Nullable Integer max) {
        return new ReviewJobRepository.PoolLoad() {
            @Override
            public long getRunning() {
                return practices == 0 ? 0 : 1;
            }

            @Override
            public long getPractices() {
                return practices;
            }

            @Override
            public UUID[] getJobIds() {
                return new UUID[0];
            }

            @Override
            public @Nullable Instant getNewestStart() {
                return null;
            }

            @Override
            public @Nullable Integer getLearnedLimit() {
                return learned;
            }

            @Override
            public @Nullable Integer getMaxConcurrentCalls() {
                return max;
            }
        };
    }

    @Test
    void shouldCountTheLearnedLimitCappedByTheMaximumLessTheInteractiveReserve() {
        assertThat(ReviewPoolAdmission.room(load(0, 8, null))).isEqualTo(6);
        assertThat(ReviewPoolAdmission.room(load(0, 12, 8)))
                .as("the maximum caps the learned limit")
                .isEqualTo(6);
        assertThat(ReviewPoolAdmission.room(load(0, 2, null))).isEqualTo(1);
    }

    @Test
    void shouldLetAWideReviewThatRunsAloneMeasureInWavesOfThePoolsRoom() {
        // A review of 32 practices that an idle pool took alone: the learned limit of 8 holds six sessions.
        assertThat(ReviewPoolAdmission.sessionLimit(load(32, 8, null), 32, 32)).isEqualTo(6);
    }

    @Test
    void shouldLeaveTheRoomOfTheOtherRunningReviewsAndAlwaysAllowOneSession() {
        assertThat(ReviewPoolAdmission.sessionLimit(load(6, 8, null), 6, 3))
                .as("three sessions of another review leave three")
                .isEqualTo(3);
        assertThat(ReviewPoolAdmission.sessionLimit(load(9, 4, null), 9, 3))
                .as("a full pool still lets the review make progress")
                .isEqualTo(1);
        assertThat(ReviewPoolAdmission.sessionLimit(load(2, 8, null), 2, 2))
                .as("never more sessions than practices")
                .isEqualTo(2);
    }
}
