package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The provider event inbox as gauges: the queued, leased and dead events of each provider, and how long the oldest due
 * event of each provider has waited. A due event that waits long means that no server claims it, or that its scope is
 * held by an event whose handler does not end. One query each pass. A failed pass keeps the last values, so a database
 * blip does not read as an empty inbox.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("The inbox backlog of the whole instance; its gauges name no workspace")
public class ProviderEventBacklogSampler {

    private static final Logger log = LoggerFactory.getLogger(ProviderEventBacklogSampler.class);
    private static final String[] STATES = {"QUEUED", "LEASED", "DEAD"};

    private final ProviderEventRepository events;
    private final Map<String, Map<String, AtomicLong>> backlog = new HashMap<>();
    private final Map<String, AtomicLong> oldestDue = new HashMap<>();
    private final Counter failures;

    ProviderEventBacklogSampler(ProviderEventRepository events, MeterRegistry meters) {
        this.events = events;
        // Every series exists from the start, so an alert on a backlog that is still empty has a series to read.
        for (IntegrationKind kind : IntegrationKind.values()) {
            String provider = kind.name().toLowerCase(Locale.ROOT);
            Map<String, AtomicLong> states = new HashMap<>();
            for (String state : STATES) {
                AtomicLong count = new AtomicLong();
                states.put(state, count);
                Gauge.builder(IntegrationCoreMetrics.INTEGRATION_CONSUMER_BACKLOG, count, AtomicLong::get)
                        .description("Provider events that are queued, leased or dead, by provider and state")
                        .tag("provider", provider)
                        .tag("state", state)
                        .register(meters);
            }
            backlog.put(provider, states);
            AtomicLong age = new AtomicLong();
            oldestDue.put(provider, age);
            Gauge.builder(IntegrationCoreMetrics.INTEGRATION_CONSUMER_OLDEST_DUE_SECONDS, age, AtomicLong::get)
                    .description("Seconds since the oldest due provider event became due; 0 when none is due")
                    .tag("provider", provider)
                    .register(meters);
        }
        failures = Counter.builder(IntegrationCoreMetrics.INTEGRATION_CONSUMER_BACKLOG_SAMPLER_FAILURES)
                .description("Provider event backlog passes that failed; the gauges kept their last values")
                .register(meters);
    }

    @Scheduled(fixedDelay = 30, initialDelay = 10, timeUnit = TimeUnit.SECONDS)
    public void sample() {
        try {
            Map<String, Map<String, Long>> counts = new HashMap<>();
            Map<String, Long> ages = new HashMap<>();
            for (var row : events.backlog()) {
                counts.computeIfAbsent(row.getProvider(), ignored -> new HashMap<>())
                        .put(row.getState(), row.getEvents());
                ages.merge(row.getProvider(), row.getOldestDueSeconds(), Math::max);
            }
            // Published only after the whole pass read, so no scrape sees a half-written inbox.
            backlog.forEach((provider, states) -> states.forEach((state, count) ->
                    count.set(counts.getOrDefault(provider, Map.of()).getOrDefault(state, 0L))));
            oldestDue.forEach((provider, age) -> age.set(ages.getOrDefault(provider, 0L)));
        } catch (RuntimeException e) {
            failures.increment();
            log.atWarn()
                    .addKeyValue("event.name", "provider_event.backlog.sampler_failed")
                    .addKeyValue("error.type", e.getClass().getName())
                    .log("Provider event backlog pass failed; the gauges keep their last values");
        }
    }
}
