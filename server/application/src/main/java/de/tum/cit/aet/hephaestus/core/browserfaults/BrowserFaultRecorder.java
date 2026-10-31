package de.tum.cit.aet.hephaestus.core.browserfaults;

import de.tum.cit.aet.hephaestus.core.browserfaults.BrowserFaultsDTOs.BrowserFaultDTO;
import de.tum.cit.aet.hephaestus.core.browserfaults.BrowserFaultsDTOs.ErrorType;
import de.tum.cit.aet.hephaestus.core.browserfaults.BrowserFaultsDTOs.Kind;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.webvitals.WebVitalsDTOs.Page;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * Writes each browser fault as one `browser.fault` log event and counts it in
 * `browser_faults_total{kind,page,error_type}`. Every value is validated before it gets here, so the
 * event carries only closed sets, a release, frame positions and a count.
 */
@ConditionalOnServerRole
@ConditionalOnBooleanProperty(BrowserFaultRecorder.ENABLED)
@Component
public class BrowserFaultRecorder {

    /** The operator's switch, `BROWSER_FAULT_REPORTS`, off by default. Off, there is no endpoint and no meter. */
    static final String ENABLED = "hephaestus.features.flags.browser-fault-reports";

    private static final Logger log = LoggerFactory.getLogger(BrowserFaultRecorder.class);

    private final MeterRegistry registry;

    public BrowserFaultRecorder(MeterRegistry registry) {
        this.registry = registry;
        // Every series exists from the start at 0, so `increase` sees the first faults after a restart.
        for (Kind kind : Kind.values()) {
            for (Page page : Page.values()) {
                for (ErrorType errorType : ErrorType.values()) {
                    counter(tag(kind), tag(page), tag(errorType));
                }
            }
        }
    }

    private Counter counter(String kind, String page, String errorType) {
        return Counter.builder(CoreMetrics.BROWSER_FAULTS)
                .description("Failures that readers met in the browser")
                .tags("kind", kind, "page", page, "error_type", errorType)
                .register(registry);
    }

    public void record(BrowserFaultDTO fault, boolean signedIn) {
        String kind = tag(fault.kind());
        String page = tag(fault.page());
        String errorType = tag(fault.errorType());
        counter(kind, page, errorType).increment(fault.count());
        log.atWarn()
                .addKeyValue("event.name", "browser.fault")
                .addKeyValue("browser.fault.kind", kind)
                .addKeyValue("browser.page", page)
                .addKeyValue("error.type", errorType)
                .addKeyValue("browser.release", fault.release())
                .addKeyValue("browser.fault.count", fault.count())
                .addKeyValue(
                        "browser.fault.frames",
                        fault.frames().stream()
                                .map(frame -> frame.chunk() + ":" + frame.line() + ":" + frame.column())
                                .collect(Collectors.joining(" ")))
                .addKeyValue("browser.signed_in", signedIn)
                .log("A reader's browser met a fault");
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
