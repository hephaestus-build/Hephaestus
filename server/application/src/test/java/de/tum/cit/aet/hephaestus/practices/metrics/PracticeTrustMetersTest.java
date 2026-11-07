package de.tum.cit.aet.hephaestus.practices.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class PracticeTrustMetersTest extends BaseUnitTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PracticeTrustMeters meters = new PracticeTrustMeters(registry);

    @Test
    void shouldExportEverySeriesAtZeroBeforeTheFirstEvent() {
        assertThat(registry.get(PracticeMetrics.RESULT_RECORDED).counters()).hasSize(4);
        assertThat(registry.get(PracticeMetrics.RESULT_MARKED).counters()).hasSize(4);
        assertThat(registry.get(PracticeMetrics.FEEDBACK_RESPONSE).counters()).hasSize(3);
    }

    @Test
    void shouldCountEachEventUnderItsClosedLabels() {
        meters.recorded(ResultOutcome.NOT_MET);
        meters.marked(ResultMarkKind.INCORRECT, false);
        meters.marked(ResultMarkKind.INCORRECT, true);
        meters.responded(FeedbackResolution.DISAGREE);

        assertThat(count(PracticeMetrics.RESULT_RECORDED, "outcome", "not_met")).isEqualTo(1);
        assertThat(count(PracticeMetrics.RESULT_MARKED, "change", "set")).isEqualTo(1);
        assertThat(count(PracticeMetrics.RESULT_MARKED, "change", "removed")).isEqualTo(1);
        assertThat(count(PracticeMetrics.FEEDBACK_RESPONSE, "resolution", "disagree"))
                .isEqualTo(1);
    }

    private double count(String name, String tag, String value) {
        return registry.find(name).tag(tag, value).counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }
}
