package de.tum.cit.aet.hephaestus.core.webvitals;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.webvitals.WebVitalsDTOs.Region;
import de.tum.cit.aet.hephaestus.core.webvitals.WebVitalsDTOs.Vital;
import de.tum.cit.aet.hephaestus.core.webvitals.WebVitalsDTOs.WebVitalDTO;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * Records page measurements as `web_vital_duration_seconds{vital,page,region}` and
 * `web_vital_layout_shift_thousandths{page,region}`. Every tag is an enum, so the series stay few. The buckets
 * include the Core Web Vitals thresholds, so an operator can read the share of good and poor visits.
 */
@ConditionalOnServerRole
@ConditionalOnBooleanProperty(WebVitalsMetrics.ENABLED)
@Component
public class WebVitalsMetrics {

    /**
     * The operator's switch, `PAGE_EXPERIENCE_METRICS`, off by default. Off, neither the endpoint nor
     * the metrics exist, and the browser measures nothing.
     */
    static final String ENABLED = "hephaestus.features.flags.page-experience-metrics";

    /**
     * The only bucket bounds. A percentile histogram would add about 70 buckets to each of the up to 54
     * tag combinations, for a measurement that needs the thresholds and a few steps between them.
     */
    private static final Duration[] DURATION_BUCKETS = {
        Duration.ofMillis(100),
        Duration.ofMillis(200), // INP good
        Duration.ofMillis(500), // INP poor
        Duration.ofMillis(800), // TTFB good
        Duration.ofMillis(1200),
        Duration.ofMillis(1800), // TTFB poor
        Duration.ofMillis(2500), // LCP good
        Duration.ofMillis(4000), // LCP poor
        Duration.ofMillis(8000),
    };

    /**
     * Layout shift in thousandths of the score, at the 0.1 and 0.25 thresholds. A histogram rounds a
     * sample up to a whole number before it compares it with a bound, so a fractional score would land
     * above both.
     */
    private static final double[] SHIFT_THRESHOLDS = {100, 250};

    private final MeterRegistry registry;

    public WebVitalsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(WebVitalDTO vital) {
        String page = tag(vital.page());
        // TTFB measures the document, not an element, so it has no region.
        String region = tag(vital.vital() == Vital.TTFB ? Region.OTHER : vital.region());
        if (vital.vital() == Vital.CLS) {
            DistributionSummary.builder(CoreMetrics.WEB_VITAL_LAYOUT_SHIFT)
                    .description("Cumulative layout shift of a page visit")
                    .baseUnit("thousandths")
                    .tags("page", page, "region", region)
                    .serviceLevelObjectives(SHIFT_THRESHOLDS)
                    .register(registry)
                    .record(vital.value() * 1000);
            return;
        }
        Timer.builder(CoreMetrics.WEB_VITAL_DURATION)
                .description("Largest contentful paint, interaction to next paint and time to first byte")
                .tags("vital", tag(vital.vital()), "page", page, "region", region)
                .serviceLevelObjectives(DURATION_BUCKETS)
                .register(registry)
                .record(Duration.ofNanos(Math.round(vital.value() * 1_000_000)));
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
