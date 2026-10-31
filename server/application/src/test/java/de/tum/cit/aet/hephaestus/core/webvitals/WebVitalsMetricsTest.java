package de.tum.cit.aet.hephaestus.core.webvitals;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.webvitals.WebVitalsDTOs.Page;
import de.tum.cit.aet.hephaestus.core.webvitals.WebVitalsDTOs.Region;
import de.tum.cit.aet.hephaestus.core.webvitals.WebVitalsDTOs.Vital;
import de.tum.cit.aet.hephaestus.core.webvitals.WebVitalsDTOs.WebVitalDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WebVitalsMetricsTest extends BaseUnitTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final WebVitalsMetrics metrics = new WebVitalsMetrics(registry);

    @Test
    void shouldRecordATimingInSecondsUnderItsVitalPageAndRegion() {
        metrics.record(new WebVitalDTO(Vital.LCP, Page.PRACTICE_PROFILE, Region.PAGE, 1250.0));

        var timer = registry.get("web.vital.duration")
                .tags("vital", "lcp", "page", "practice_profile", "region", "page")
                .timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1250);
    }

    @Test
    void shouldKeepATimingToItsNineBucketsSoTheSeriesStayFew() {
        metrics.record(new WebVitalDTO(Vital.INP, Page.ASK_HEPHAESTUS, Region.HEADER, 150.0));

        var buckets = registry.get("web.vital.duration").timer().takeSnapshot().histogramCounts();
        assertThat(buckets)
                .extracting(bucket -> bucket.bucket(TimeUnit.MILLISECONDS))
                .containsExactly(100.0, 200.0, 500.0, 800.0, 1200.0, 1800.0, 2500.0, 4000.0, 8000.0);
        assertThat(buckets)
                .extracting(bucket -> bucket.count())
                .containsExactly(0.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
    }

    @Test
    void shouldRecordALayoutShiftInThousandthsWithoutAVitalTag() {
        metrics.record(new WebVitalDTO(Vital.CLS, Page.FEEDBACK_APPROVAL, Region.SIDEBAR, 0.07));

        var shift = registry.get("web.vital.layout_shift")
                .tags("page", "feedback_approval", "region", "sidebar")
                .summary();
        assertThat(shift.count()).isEqualTo(1);
        assertThat(shift.totalAmount()).isEqualTo(70);
        assertThat(registry.find("web.vital.duration").timer()).isNull();
    }

    @Test
    void shouldCountALayoutShiftAsGoodOrPoorAtTheCoreWebVitalsThresholds() {
        for (double score : new double[] {0.01, 0.1, 0.1001, 0.25, 0.2501, 0.3}) {
            metrics.record(new WebVitalDTO(Vital.CLS, Page.OTHER, Region.PAGE, score));
        }

        var buckets =
                registry.get("web.vital.layout_shift").summary().takeSnapshot().histogramCounts();
        assertThat(buckets).extracting(bucket -> bucket.bucket()).containsExactly(100.0, 250.0);
        assertThat(buckets).extracting(bucket -> bucket.count()).containsExactly(2.0, 4.0);
    }

    @Test
    void shouldRecordTimeToFirstByteWithoutARegion() {
        metrics.record(new WebVitalDTO(Vital.TTFB, Page.ACTIVITY, Region.SIDEBAR, 300.0));

        assertThat(registry.get("web.vital.duration")
                        .tags("vital", "ttfb", "page", "activity", "region", "other")
                        .timer()
                        .count())
                .isEqualTo(1);
    }
}
