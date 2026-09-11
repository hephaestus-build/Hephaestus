package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class SurveyTest {
    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");

    @Test
    void shouldBeOpenOnlyWhileActiveInsideTheScheduleAndForTheTargetedWorkspace() {
        Survey survey = new Survey("t", "d", new ObjectMapper().createArrayNode(), 7L, NOW.minusSeconds(1), null, 1L);
        assertThat(survey.isOpenFor(7L, NOW)).isTrue();
        assertThat(survey.isOpenFor(8L, NOW)).isFalse();

        survey.edit("t", "d", NOW.minusSeconds(1), null, false);
        assertThat(survey.isOpenFor(7L, NOW)).as("paused").isFalse();

        survey.edit("t", "d", NOW.plusSeconds(1), null, true);
        assertThat(survey.isOpenFor(7L, NOW)).as("not started").isFalse();

        survey.edit("t", "d", NOW.minusSeconds(2), NOW, true);
        assertThat(survey.isOpenFor(7L, NOW)).as("ended").isFalse();

        survey.edit("t", "d", NOW, NOW.plusSeconds(1), true);
        assertThat(survey.isOpenFor(7L, NOW)).as("open at the start instant").isTrue();
    }

    @Test
    void shouldBeOpenForEveryWorkspaceWhenNotTargeted() {
        Survey survey = new Survey("t", "d", new ObjectMapper().createArrayNode(), null, NOW, null, 1L);
        assertThat(survey.isOpenFor(7L, NOW)).isTrue();
        assertThat(survey.isOpenFor(8L, NOW)).isTrue();
    }
}
