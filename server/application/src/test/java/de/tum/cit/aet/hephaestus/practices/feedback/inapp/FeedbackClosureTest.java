package de.tum.cit.aet.hephaestus.practices.feedback.inapp;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.feedback.inapp.FeedbackClosure.ClosedBy;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class FeedbackClosureTest {

    private static final Instant EARLIER = Instant.parse("2026-05-01T12:00:00Z");
    private static final Instant LATER = Instant.parse("2026-05-02T12:00:00Z");

    @Test
    void shouldStayOpenWhenNothingClosedIt() {
        assertThat(FeedbackClosure.of(null, null, null)).isNull();
    }

    @Test
    void shouldCloseAtThePracticeChangeWhenItCameBeforeTheAnswer() {
        assertThat(FeedbackClosure.of(null, LATER, EARLIER))
                .isEqualTo(new FeedbackClosure(EARLIER, ClosedBy.PRACTICE_CHANGED));
    }

    @Test
    void shouldGiveATieToTheWorkThenTheDeveloperWhenTheyHappenedAtOnce() {
        assertThat(FeedbackClosure.of(EARLIER, EARLIER, EARLIER))
                .isEqualTo(new FeedbackClosure(EARLIER, ClosedBy.WORK));
        assertThat(FeedbackClosure.of(null, EARLIER, EARLIER))
                .isEqualTo(new FeedbackClosure(EARLIER, ClosedBy.DEVELOPER));
    }
}
