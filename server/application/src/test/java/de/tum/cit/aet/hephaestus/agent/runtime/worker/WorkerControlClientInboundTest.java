package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.CapacityReport;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.FrameCodec;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.Heartbeat;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.WorkerHello;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * The hub answers every capacity report with a heartbeat, so on an idle channel the worker receives
 * one every reporting interval. That echo is the traffic the silence deadline waits for and must not
 * read as a protocol violation.
 */
class WorkerControlClientInboundTest extends BaseUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkerControlClient client = new WorkerControlClient(
            WorkerPropertiesFixtures.minimal("2", "1"),
            new FrameCodec(objectMapper),
            objectMapper,
            new SimpleMeterRegistry());

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private @Nullable Level originalLevel;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(WorkerControlClient.class);
        originalLevel = logger.getLevel();
        // Pin the level so "no warning" is a fact about the code and not about the ambient setup.
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
    }

    @Test
    void acceptsHubHeartbeatWithoutWarning() {
        client.handleInbound(new Heartbeat(false));

        assertThat(warnings()).isEmpty();
    }

    @Test
    void reportsEachFrameOnlyAWorkerSends() {
        client.handleInbound(new CapacityReport(2, 0, 1, 0, 2, 1));
        client.handleInbound(new WorkerHello("w", List.of(1), "0.0-test"));

        assertThat(warnings())
                .hasSize(2)
                .allSatisfy(message -> assertThat(message).contains("Unexpected worker-source frame"));
    }

    /** Anything below WARN is noise the worker is free to add or drop; the report is the contract. */
    private List<String> warnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
