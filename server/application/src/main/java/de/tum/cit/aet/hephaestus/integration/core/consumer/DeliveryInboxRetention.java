package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keep keys beyond both configured and live stream retention; limit reductions can be withheld by the broker host. */
@Component
@ConditionalOnServerRole
public class DeliveryInboxRetention {
    private static final Logger log = LoggerFactory.getLogger(DeliveryInboxRetention.class);
    private static final Duration MARGIN = Duration.ofDays(1);

    private final DeliveryInbox inbox;
    private final WebhookProperties properties;
    private final Clock clock;
    private final @Nullable Connection connection;
    private final Counter failures;

    public DeliveryInboxRetention(
            DeliveryInbox inbox,
            WebhookProperties properties,
            Clock clock,
            MeterRegistry meters,
            @Qualifier("natsConnection") @Nullable Connection connection) {
        this.inbox = inbox;
        this.properties = properties;
        this.clock = clock;
        this.connection = connection;
        this.failures = Counter.builder(IntegrationCoreMetrics.INBOX_RETENTION_FAILURES)
                .description("Inbox retention runs that failed")
                .register(meters);
    }

    @Scheduled(cron = "0 45 */6 * * *")
    @SchedulerLock(name = "integration-inbox-retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @WorkspaceAgnostic("Retention prunes expired delivery steps across every workspace and the instance")
    public void prune() {
        try {
            if (connection == null) return;
            Duration retention = properties.stream().maxAge();
            for (Duration override : properties.stream().maxAgeByStream().values()) {
                if (override.compareTo(retention) > 0) retention = override;
            }
            var broker = connection.jetStreamManagement();
            for (IntegrationKind kind : IntegrationKind.values()) {
                try {
                    Duration live = broker.getStreamInfo(kind.name().toLowerCase(Locale.ROOT))
                            .getConfiguration()
                            .getMaxAge();
                    if (live.isZero() || live.isNegative()) {
                        throw new IllegalStateException("Inbox retention requires bounded streams");
                    }
                    if (live.compareTo(retention) > 0) retention = live;
                } catch (JetStreamApiException missing) {
                    if (missing.getErrorCode() != 404) throw missing;
                }
            }
            inbox.deleteExpired(clock.instant().minus(retention.plus(MARGIN)));
        } catch (IOException | JetStreamApiException | RuntimeException failure) {
            failures.increment();
            log.warn("Inbox retention failed: error.type={}", failure.getClass().getName());
        }
    }
}
