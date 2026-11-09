package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.nats.client.Message;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** How long provider events wait for a consumer, and how long their handling takes. */
@Component
@ConditionalOnServerRole
public class IntegrationMessageMeters {

    private static final Logger log = LoggerFactory.getLogger(IntegrationMessageMeters.class);

    private static final Duration[] LAG_BOUNDS = {
        Duration.ofMillis(100),
        Duration.ofMillis(500),
        Duration.ofSeconds(1),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10),
        Duration.ofSeconds(30),
        Duration.ofMinutes(1),
        Duration.ofMinutes(5),
        Duration.ofMinutes(15),
        Duration.ofHours(1)
    };

    private static final Duration[] PROCESS_BOUNDS = {
        Duration.ofMillis(5),
        Duration.ofMillis(10),
        Duration.ofMillis(25),
        Duration.ofMillis(50),
        Duration.ofMillis(100),
        Duration.ofMillis(250),
        Duration.ofMillis(500),
        Duration.ofSeconds(1),
        Duration.ofMillis(2500),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10),
        Duration.ofSeconds(30),
        Duration.ofMinutes(1)
    };

    private final MeterRegistry registry;
    private final Clock clock;

    public IntegrationMessageMeters(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    /**
     * A first delivery only: the wait of a retry is its backoff, and the nak counter reports the retry. A measurement
     * never stops the handling of an event, so neither method lets a failure out.
     */
    void received(Message message) {
        try {
            if (!message.isJetStream()) return;
            var metadata = message.metaData();
            if (metadata.deliveredCount() > 1) return;
            Timer.builder(IntegrationCoreMetrics.INTEGRATION_MESSAGE_LAG)
                    .description("How long an event waited in its stream before its first handling")
                    .tag("kind", IntegrationPoisonHandler.kindTag(message))
                    .serviceLevelObjectives(LAG_BOUNDS)
                    .register(registry)
                    .record(Duration.between(metadata.timestamp().toInstant(), clock.instant()));
        } catch (RuntimeException failure) {
            log.debug(
                    "Skipped an event's wait: error.type={}", failure.getClass().getName());
        }
    }

    /** How the handling of one delivery ended. */
    enum Outcome {
        COMPLETED("completed"),
        FAILED("failed"),
        /** The handler outlived the handler timeout, and its event returned to the stream. */
        TIMED_OUT("timed_out");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }
    }

    void handled(Message message, Outcome outcome, long nanos) {
        try {
            Timer.builder(IntegrationCoreMetrics.INTEGRATION_MESSAGE_PROCESS_DURATION)
                    .description("How long the handling of one delivery took")
                    .tag("kind", IntegrationPoisonHandler.kindTag(message))
                    .tag("outcome", outcome.tag)
                    .serviceLevelObjectives(PROCESS_BOUNDS)
                    .register(registry)
                    .record(Duration.ofNanos(nanos));
        } catch (RuntimeException failure) {
            log.debug(
                    "Skipped a handling time: error.type={}", failure.getClass().getName());
        }
    }
}
