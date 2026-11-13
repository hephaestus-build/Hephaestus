package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
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
    private final Counter retentionFailures;

    public IntegrationMessageMeters(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        this.retentionFailures = Counter.builder(IntegrationCoreMetrics.INBOX_RETENTION_FAILURES)
                .description("Provider event retention passes that failed")
                .register(registry);
        // A series that a timeout creates starts at 1, and increase() reads that first sample as no increase.
        for (IntegrationKind kind : IntegrationKind.values()) {
            processTimer(kind.name().toLowerCase(Locale.ROOT), Outcome.TIMED_OUT);
        }
    }

    /** One failed retention pass; the next pass deletes what this one left. */
    void retentionFailed() {
        retentionFailures.increment();
    }

    /**
     * A first attempt only: the wait of a retry is its backoff, and the retry counter reports the retry. A measurement
     * never stops the handling of an event, so neither method lets a failure out.
     */
    void received(ProviderEvent event, int attempt) {
        try {
            if (attempt > 1) return;
            Timer.builder(IntegrationCoreMetrics.INTEGRATION_MESSAGE_LAG)
                    .description("How long an event waited in the inbox before its first handling")
                    .tag("kind", IntegrationPoisonHandler.kindTag(event.subject()))
                    .serviceLevelObjectives(LAG_BOUNDS)
                    .register(registry)
                    .record(Duration.between(event.receivedAt(), clock.instant()));
        } catch (RuntimeException failure) {
            log.debug(
                    "Skipped an event's wait: error.type={}", failure.getClass().getName());
        }
    }

    /** How the handling of one delivery ended. */
    enum Outcome {
        COMPLETED("completed"),
        FAILED("failed"),
        RETRIED("retried"),
        DROPPED("dropped"),
        /** The handler outlived the handler timeout, and its event returned to the inbox. */
        TIMED_OUT("timed_out");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }
    }

    void handled(ProviderEvent event, Outcome outcome, long nanos) {
        try {
            processTimer(IntegrationPoisonHandler.kindTag(event.subject()), outcome)
                    .record(Duration.ofNanos(nanos));
        } catch (RuntimeException failure) {
            log.debug(
                    "Skipped a handling time: error.type={}", failure.getClass().getName());
        }
    }

    void dropped(ProviderEvent event, ProviderEventFenceException.Reason reason, long nanos) {
        Counter.builder(IntegrationCoreMetrics.INTEGRATION_MESSAGE_DROPPED)
                .description("Provider event attempts dropped by the erasure or lease fence")
                .tag("kind", IntegrationPoisonHandler.kindTag(event.subject()))
                .tag("reason", reason.tag)
                .register(registry)
                .increment();
        handled(event, Outcome.DROPPED, nanos);
    }

    private Timer processTimer(String kind, Outcome outcome) {
        return Timer.builder(IntegrationCoreMetrics.INTEGRATION_MESSAGE_PROCESS_DURATION)
                .description("How long the handling of one delivery took")
                .tag("kind", kind)
                .tag("outcome", outcome.tag)
                .serviceLevelObjectives(PROCESS_BOUNDS)
                .register(registry);
    }
}
