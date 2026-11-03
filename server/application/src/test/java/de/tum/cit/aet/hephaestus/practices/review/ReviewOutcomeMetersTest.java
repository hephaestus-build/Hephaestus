package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ReviewOutcomeMetersTest {

    @Test
    void shouldLabelARecordedConstantAndCountAnyOtherTextAsOther() {
        assertThat(ReviewOutcomeMeters.reason("EXECUTION_FAILED")).isEqualTo("execution_failed");
        assertThat(ReviewOutcomeMeters.reason("consulted_source_unread")).isEqualTo("consulted_source_unread");
        assertThat(ReviewOutcomeMeters.reason(null)).isEqualTo("none");
        assertThat(ReviewOutcomeMeters.reason("CHECKS_PENDING:42")).isEqualTo("other");
        assertThat(ReviewOutcomeMeters.reason("The checks did not settle")).isEqualTo("other");
        assertThat(ReviewOutcomeMeters.reason("A".repeat(49))).isEqualTo("other");
    }
}
