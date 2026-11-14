package de.tum.cit.aet.hephaestus.practices.review.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewItemLifecycleTest extends BaseUnitTest {
    private static final UUID RESULT = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Test
    void shouldStageAndJudgeAMembershipWhenEachMoveIsLegal() {
        var member = new ReviewItem();
        member.stage();
        member.judge(RESULT);

        assertThat(member.getState()).isEqualTo(ReviewItemState.JUDGED);
        assertThat(member.getResultId()).isEqualTo(RESULT);
    }

    @Test
    void shouldWithholdAStagedMembershipWithItsReasonAndMessage() {
        var member = new ReviewItem();
        member.stage();
        member.withhold("EXECUTION_FAILED", "The practice script stopped.", Instant.EPOCH);

        assertThat(member.getState()).isEqualTo(ReviewItemState.WITHHELD);
        assertThat(member.getReason()).isEqualTo("EXECUTION_FAILED");
        assertThat(member.getAttemptedAt()).isEqualTo(Instant.EPOCH);
        var detail = member.getDetail();
        assertThat(detail).isNotNull();
        assertThat(detail.path("withholdingMessage").asString()).isEqualTo("The practice script stopped.");
    }

    @Test
    void shouldRefuseToReuseAResultForAStagedMembership() {
        var member = new ReviewItem();
        member.stage();

        assertThatIllegalStateException().isThrownBy(() -> member.reuse(RESULT));
        assertThat(member.getState()).isEqualTo(ReviewItemState.STAGED);
        assertThat(member.getResultId()).isNull();
    }

    @Test
    void shouldRefuseEveryMoveWhenTheMembershipHasAnOutcome() {
        var judged = new ReviewItem();
        judged.judge(RESULT);
        var reused = new ReviewItem();
        reused.reuse(RESULT);
        var withheld = new ReviewItem();
        withheld.withhold("PLAN_REFUSED");

        for (var member : List.of(judged, reused, withheld)) {
            var before = member.getState();
            assertThatIllegalStateException().isThrownBy(member::stage);
            assertThatIllegalStateException().isThrownBy(() -> member.judge(UUID.randomUUID()));
            assertThatIllegalStateException().isThrownBy(() -> member.reuse(UUID.randomUUID()));
            assertThatIllegalStateException().isThrownBy(() -> member.withhold("LATE"));
            assertThat(member.getState()).isEqualTo(before);
        }
        assertThat(judged.getResultId()).isEqualTo(RESULT);
        assertThat(withheld.getReason()).isEqualTo("PLAN_REFUSED");
    }
}
