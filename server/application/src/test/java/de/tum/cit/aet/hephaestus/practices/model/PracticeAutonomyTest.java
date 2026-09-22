package de.tum.cit.aet.hephaestus.practices.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Practice autonomy")
class PracticeAutonomyTest extends BaseUnitTest {

    @Nested
    @DisplayName("admission")
    class Admission {

        @Test
        void offIsTheOnlyTierThatStopsAReview() {
            assertThat(PracticeAutonomy.OFF.admitsReview()).isFalse();
            assertThat(PracticeAutonomy.HUMAN_APPROVAL.admitsReview()).isTrue();
            assertThat(PracticeAutonomy.AUTOMATIC.admitsReview()).isTrue();
        }
    }

    @Nested
    @DisplayName("autonomy")
    class Autonomy {

        @Test
        void shouldOnlyDeliverAutomaticallyAtAutomaticAutonomy() {
            assertThat(PracticeAutonomy.OFF.deliversWithoutApproval()).isFalse();
            assertThat(PracticeAutonomy.HUMAN_APPROVAL.deliversWithoutApproval())
                    .isFalse();
            assertThat(PracticeAutonomy.AUTOMATIC.deliversWithoutApproval()).isTrue();
        }

        @Test
        void shouldRequireApprovalAtHumanApprovalAutonomyForThePushedChannelOnly() {
            assertThat(PracticeAutonomy.HUMAN_APPROVAL.deliversWithoutApproval())
                    .isFalse();
            assertThat(PracticeAutonomy.HUMAN_APPROVAL.delivers(FeedbackChannel.IN_CONTEXT))
                    .isFalse();
            assertThat(PracticeAutonomy.HUMAN_APPROVAL.delivers(FeedbackChannel.IN_APP))
                    .isTrue();
            assertThat(PracticeAutonomy.HUMAN_APPROVAL.delivers(FeedbackChannel.IN_CHAT))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("the shape of the ladder")
    class LadderShape {

        @Test
        void shouldExposeOnlyTheThreeCanonicalAutonomies() {
            assertThat(PracticeAutonomy.values())
                    .containsExactly(PracticeAutonomy.OFF, PracticeAutonomy.HUMAN_APPROVAL, PracticeAutonomy.AUTOMATIC);
        }
    }

    @Nested
    @DisplayName("persistence shape")
    class PersistenceShape {

        @Test
        void everyConstantFitsTheColumn() {
            assertThat(Arrays.stream(PracticeAutonomy.values()).map(Enum::name).map(String::length))
                    .allSatisfy(length -> assertThat(length).isLessThanOrEqualTo(PracticeAutonomy.MAX_LENGTH));
        }

        @Test
        void shouldFailSafeByDefault() {
            assertThat(PracticeAutonomy.DEFAULT).isEqualTo(PracticeAutonomy.HUMAN_APPROVAL);
        }
    }
}
