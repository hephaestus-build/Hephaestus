package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.feedback.PreviousInAppFeedback.Previous;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PreviousInAppFeedbackTest {

    private static final Instant WINDOW_START = Instant.parse("2026-05-01T12:00:00Z");

    @Test
    void shouldStartAfterThePreparationWhenTheOpenCardIsInsideTheWindow() {
        Instant preparedAt = WINDOW_START.plus(Duration.ofDays(3));

        assertThat(new Previous(UUID.randomUUID(), preparedAt, null).nextEvidenceSince(WINDOW_START))
                .isEqualTo(preparedAt);
    }

    @Test
    void shouldStartWhereTheCardClosedWhenItClosed() {
        Instant closedAt = WINDOW_START.plus(Duration.ofDays(5));

        assertThat(new Previous(UUID.randomUUID(), WINDOW_START.plus(Duration.ofDays(1)), closedAt)
                        .nextEvidenceSince(WINDOW_START))
                .isEqualTo(closedAt);
    }

    @Test
    void shouldStartAtTheWindowWhenTheCardIsOlderThanTheWindow() {
        Instant preparedAt = WINDOW_START.minus(Duration.ofDays(100));

        assertThat(new Previous(UUID.randomUUID(), preparedAt, null).nextEvidenceSince(WINDOW_START))
                .isEqualTo(WINDOW_START);
        assertThat(new Previous(UUID.randomUUID(), preparedAt, preparedAt.plus(Duration.ofDays(1)))
                        .nextEvidenceSince(WINDOW_START))
                .isEqualTo(WINDOW_START);
    }
}
