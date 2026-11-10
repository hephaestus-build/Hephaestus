package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.notification.metrics.NotificationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Counts, by listener, the event publications still open {@link #OVERDUE_AFTER} after they were published.
 * {@link NotificationRedeliveryJob} retries a failed publication without a limit, so one that its listener can never
 * complete is otherwise invisible. Every replica measures, so the gauge does not depend on which replica holds the
 * redelivery lock.
 */
@Component
@ConditionalOnServerRole
@Profile("!specs & !cds-training")
@WorkspaceAgnostic("Counts the instance-wide publication registry by listener; reads no workspace data")
public class OverduePublicationMeter {

    private static final Logger log = LoggerFactory.getLogger(OverduePublicationMeter.class);

    static final Duration OVERDUE_AFTER = Duration.ofHours(1);

    private final EventPublicationRepository publications;
    private final Clock clock;
    private final MultiGauge overdue;

    public OverduePublicationMeter(EventPublicationRepository publications, Clock clock, MeterRegistry meterRegistry) {
        this.publications = publications;
        this.clock = clock;
        this.overdue = MultiGauge.builder(NotificationMetrics.EVENT_PUBLICATIONS_OVERDUE)
                .description("Event publications still not completed one hour after they were published, by listener")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void measure() {
        Map<String, Long> byListener = new TreeMap<>();
        try {
            for (var publication : publications.findIncompletePublicationsPublishedBefore(
                    clock.instant().minus(OVERDUE_AFTER))) {
                byListener.merge(listenerName(publication.getTargetIdentifier().getValue()), 1L, Long::sum);
            }
        } catch (RuntimeException e) {
            // Keep the last counts: a database blip must not read as "nothing is overdue".
            log.warn(
                    "Overdue event publication count failed, keeping last values: errorType={}",
                    e.getClass().getName());
            return;
        }
        overdue.register(
                byListener.entrySet().stream()
                        .map(entry -> MultiGauge.Row.of(Tags.of("listener", entry.getKey()), entry.getValue()))
                        .toList(),
                true);
    }

    /** {@code Type.method}: the listener id is the method's full signature, too long for a label. */
    static String listenerName(String listenerId) {
        int parameters = listenerId.indexOf('(');
        String method = parameters < 0 ? listenerId : listenerId.substring(0, parameters);
        int methodDot = method.lastIndexOf('.');
        int typeDot = methodDot < 0 ? -1 : method.lastIndexOf('.', methodDot - 1);
        return method.substring(typeDot + 1);
    }
}
