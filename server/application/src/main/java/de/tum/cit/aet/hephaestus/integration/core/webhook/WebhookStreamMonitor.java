package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ConsumerSubjectMath;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.ConsumerInfo;
import io.nats.client.api.StreamInfo;
import io.nats.client.api.StreamState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports each webhook stream's usage, and the backlog this deployment's durables have on it.
 *
 * <p>Webhook loss is not measured. A durable filtered to a few subjects advances its ack floor only over
 * messages it matches, so on a shared stream shedding at its bound, comparing that floor with the
 * stream's first sequence counts other subjects' messages as its loss. Stream and consumer snapshots
 * cannot say which shed messages matched which filter, so current backlog is published instead.
 *
 * <p>Two things keep that worth alerting on, and both are about a broker that may be shared. Only
 * durables under {@code hephaestus.sync.nats.durable-consumer-name} are counted, and every meter is
 * tagged by stream alone and registered once at construction, so the series count is fixed at four
 * however many consumers, workspaces or stacks come and go.
 *
 * <p>Runs its own single-threaded scheduler rather than {@code @Scheduled}: {@code @EnableScheduling}
 * lives on the SERVER-gated scheduling config, and this bean is contributed on the WEBHOOK role,
 * where an annotated method would silently never tick.
 */
class WebhookStreamMonitor {

    private static final Logger log = LoggerFactory.getLogger(WebhookStreamMonitor.class);
    private static final Usage UNKNOWN = new Usage(0, 0, 0, 0, 0, 0, 0, 0);

    private final JetStreamManagement jsm;
    private final WebhookProperties properties;
    private final String durablePrefix;
    private final Map<String, Usage> usage = new ConcurrentHashMap<>();
    /**
     * When each stream's gauges were last refreshed. A monitor that cannot read the broker holds them at
     * their last values, which read exactly like current ones — so the age of this is what says whether
     * they are being maintained at all.
     */
    private final Map<String, AtomicLong> lastSuccessfulPollMillis = new HashMap<>();

    private final Map<String, Boolean> failing = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "webhook-stream-monitor");
        thread.setDaemon(true);
        return thread;
    });

    WebhookStreamMonitor(
            JetStreamManagement jsm,
            WebhookProperties properties,
            String durableConsumerName,
            MeterRegistry meterRegistry) {
        this.jsm = jsm;
        this.properties = properties;
        this.durablePrefix = ConsumerSubjectMath.durablePrefix(durableConsumerName);
        for (String name : WebhookJetStreamBootstrap.STREAMS) {
            usage.put(name, UNKNOWN);
            Tags tags = Tags.of("stream", name);
            gauge(meterRegistry, IntegrationCoreMetrics.WEBHOOK_STREAM_BYTES, tags, name, Usage::bytes);
            gauge(meterRegistry, IntegrationCoreMetrics.WEBHOOK_STREAM_BYTES_LIMIT, tags, name, Usage::maxBytes);
            gauge(
                    meterRegistry,
                    IntegrationCoreMetrics.WEBHOOK_STREAM_BYTES_UTILIZATION,
                    tags,
                    name,
                    Usage::utilization);
            gauge(meterRegistry, IntegrationCoreMetrics.WEBHOOK_STREAM_MESSAGES, tags, name, Usage::messages);
            // Effective retention, measured rather than claimed: max-age is a ceiling and max-bytes is
            // the floor under it, so which one a deployment actually gets is a function of its volume.
            Gauge.builder(
                            IntegrationCoreMetrics.WEBHOOK_STREAM_OLDEST_MESSAGE_AGE,
                            this,
                            monitor -> monitor.usage.getOrDefault(name, UNKNOWN).oldestMessageAgeSeconds())
                    .tags(tags)
                    .baseUnit("seconds")
                    .register(meterRegistry);
            // A durable nobody deletes shows up here as a count that only ever climbs.
            gauge(meterRegistry, IntegrationCoreMetrics.WEBHOOK_STREAM_CONSUMERS, tags, name, Usage::consumers);
            gauge(meterRegistry, IntegrationCoreMetrics.WEBHOOK_STREAM_CONSUMER_PENDING, tags, name, Usage::pending);
            gauge(
                    meterRegistry,
                    IntegrationCoreMetrics.WEBHOOK_STREAM_CONSUMER_ACK_PENDING,
                    tags,
                    name,
                    Usage::ackPending);
            gauge(
                    meterRegistry,
                    IntegrationCoreMetrics.WEBHOOK_STREAM_CONSUMERS_WITHOUT_PULL_REQUESTS,
                    tags,
                    name,
                    Usage::withoutPullRequests);

            AtomicLong polled = new AtomicLong();
            lastSuccessfulPollMillis.put(name, polled);
            Gauge.builder(IntegrationCoreMetrics.WEBHOOK_STREAM_POLL_AGE, polled, WebhookStreamMonitor::secondsSince)
                    .tags(tags)
                    .baseUnit("seconds")
                    .register(meterRegistry);
        }
    }

    private void gauge(MeterRegistry registry, String metric, Tags tags, String stream, ToDoubleFunction<Usage> read) {
        Gauge.builder(metric, this, monitor -> read.applyAsDouble(monitor.usage.getOrDefault(stream, UNKNOWN)))
                .tags(tags)
                .register(registry);
    }

    @PostConstruct
    void start() {
        long intervalMs = properties.stream().monitorInterval().toMillis();
        // Zero initial delay: the first read happens on the scheduler thread, so it populates the
        // gauges immediately without adding NATS round-trips to application startup.
        scheduler.scheduleWithFixedDelay(this::poll, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
        // Context teardown must not leave a poll using the broker or logging after this bean is gone.
        scheduler.close();
    }

    /** Package-private so the gauges are testable without waiting on the scheduler. */
    void poll() {
        for (String name : WebhookJetStreamBootstrap.STREAMS) {
            try {
                StreamInfo info = jsm.getStreamInfo(name);
                StreamState state = info.getStreamState();
                if (state == null) {
                    failed(name, new IllegalStateException("stream state unavailable"));
                    continue;
                }
                long pending = 0;
                long ackPending = 0;
                long withoutPullRequests = 0;
                for (ConsumerInfo consumer : jsm.getConsumers(name)) {
                    if (!consumer.getName().startsWith(durablePrefix)) {
                        // Another deployment's durable. An abandoned one keeps its backlog for good, so
                        // counting it pegs these gauges at a number nobody here can act on.
                        continue;
                    }
                    // Per durable, so a message matching two durables counts twice.
                    pending += consumer.getNumPending();
                    ackPending += consumer.getNumAckPending();
                    if (consumer.getNumWaiting() == 0) {
                        withoutPullRequests++;
                    }
                }
                usage.put(
                        name,
                        new Usage(
                                state.getByteCount(),
                                state.getMsgCount(),
                                info.getConfiguration().getMaxBytes(),
                                state.getConsumerCount(),
                                ageSeconds(state),
                                pending,
                                ackPending,
                                withoutPullRequests));
                Objects.requireNonNull(lastSuccessfulPollMillis.get(name)).set(System.currentTimeMillis());
                recovered(name);
            } catch (Exception e) {
                failed(name, e);
            }
        }
    }

    /**
     * The monitor must not fail silently itself. The first failure and the recovery are both above DEBUG;
     * the repetitions in between are not, so a long broker outage does not bury everything else.
     */
    private void failed(String stream, Exception e) {
        if (failing.put(stream, Boolean.TRUE) == null) {
            log.warn(
                    "Webhook stream monitoring stopped for stream {}: its gauges hold their last values "
                            + "until this recovers ({}: {})",
                    stream,
                    e.getClass().getSimpleName(),
                    e.getMessage());
            return;
        }
        log.debug("Stream usage poll still failing: stream={}, error={}", stream, e.getMessage());
    }

    private void recovered(String stream) {
        if (failing.remove(stream) != null) {
            log.info("Webhook stream monitoring resumed for stream {}", stream);
        }
    }

    private static long ageSeconds(StreamState state) {
        ZonedDateTime first = state.getFirstTime();
        if (state.getMsgCount() == 0 || first == null) {
            return 0;
        }
        return Math.max(0, Duration.between(first.toInstant(), Instant.now()).getSeconds());
    }

    private static double secondsSince(AtomicLong millis) {
        long last = millis.get();
        return last == 0 ? Double.NaN : (System.currentTimeMillis() - last) / 1000d;
    }

    /** {@code maxBytes <= 0} is JetStream's encoding of "unbounded". */
    record Usage(
            long bytes,
            long messages,
            long maxBytes,
            long consumers,
            long oldestMessageAgeSeconds,
            long pending,
            long ackPending,
            long withoutPullRequests) {
        double utilization() {
            return maxBytes > 0 ? (double) bytes / maxBytes : 0d;
        }
    }
}
