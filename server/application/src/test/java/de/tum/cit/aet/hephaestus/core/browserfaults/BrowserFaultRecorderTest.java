package de.tum.cit.aet.hephaestus.core.browserfaults;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.core.browserfaults.BrowserFaultsDTOs.BrowserFaultDTO;
import de.tum.cit.aet.hephaestus.core.browserfaults.BrowserFaultsDTOs.ErrorType;
import de.tum.cit.aet.hephaestus.core.browserfaults.BrowserFaultsDTOs.FrameDTO;
import de.tum.cit.aet.hephaestus.core.browserfaults.BrowserFaultsDTOs.Kind;
import de.tum.cit.aet.hephaestus.core.webvitals.WebVitalsDTOs.Page;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class BrowserFaultRecorderTest extends BaseUnitTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BrowserFaultRecorder recorder = new BrowserFaultRecorder(registry);

    @Test
    void shouldExposeEverySeriesAtZeroBeforeTheFirstFault() {
        var counters = registry.find("browser.faults").counters();

        assertThat(counters).hasSize(Kind.values().length * Page.values().length * ErrorType.values().length);
        assertThat(counters).allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    void shouldCountAFaultByItsClosedTagsAndWriteOneEventWithItsFrames() {
        Logger logger = (Logger) LoggerFactory.getLogger(BrowserFaultRecorder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            recorder.record(
                    new BrowserFaultDTO(
                            Kind.UNHANDLED_REJECTION,
                            Page.FEEDBACK,
                            ErrorType.TYPE_ERROR,
                            "0.95.1",
                            List.of(new FrameDTO("CRYwA6-M", 1, 2345), new FrameDTO("DqCAn3iK", 3, 17)),
                            3),
                    false);

            assertThat(registry.get("browser.faults")
                            .tags("kind", "unhandled_rejection", "page", "feedback", "error_type", "type_error")
                            .counter()
                            .count())
                    .isEqualTo(3);
            assertThat(appender.list).hasSize(1);
            Map<String, Object> event = appender.list.getFirst().getKeyValuePairs().stream()
                    .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
            assertThat(event)
                    .containsEntry("event.name", "browser.fault")
                    .containsEntry("browser.fault.kind", "unhandled_rejection")
                    .containsEntry("browser.page", "feedback")
                    .containsEntry("error.type", "type_error")
                    .containsEntry("browser.release", "0.95.1")
                    .containsEntry("browser.fault.count", 3)
                    .containsEntry("browser.fault.frames", "CRYwA6-M:1:2345 DqCAn3iK:3:17")
                    .containsEntry("browser.signed_in", false);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
