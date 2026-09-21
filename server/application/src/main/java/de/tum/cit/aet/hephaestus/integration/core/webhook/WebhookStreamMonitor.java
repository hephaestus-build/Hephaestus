package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ConsumerSubjectMath;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.ConsumerInfo;
import io.nats.client.api.SequenceInfo;
import io.nats.client.api.StreamInfo;
import io.nats.client.api.StreamState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
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
 * Measures webhook loss, and reports stream usage alongside it.
 *
 * <p>The signal that matters is {@code webhook.stream.unacknowledged.deletions}: JetStream's first
 * stored sequence against each consumer's ack floor. A message below the first sequence that the
 * consumer never acknowledged has been deleted before anyone read it, and a push event lost that way
 * is not redeliverable by either provider (ADR 0008). It is a counter, it is zero unless something is
 * genuinely wrong, and it does not go quiet at steady state the way proximity to a bound does.
 *
 * <p>Two things keep it worth alerting on, and both are about a broker that may be shared. Loss is
 * charged only to durables under {@code hephaestus.sync.nats.durable-consumer-name}, and every meter
 * is tagged by stream alone and registered once at construction, so the series count is fixed at four
 * however many consumers, workspaces or stacks come and go.
 *
 * <p>Runs its own single-threaded scheduler rather than {@code @Scheduled}: {@code @EnableScheduling}
 * lives on the SERVER-gated scheduling config, and this bean is contributed on the WEBHOOK role,
 * where an annotated method would silently never tick.
 */
class WebhookStreamMonitor {

    private static final Logger log = LoggerFactory.getLogger(WebhookStreamMonitor.class);
    private static final Usage UNKNOWN = new Usage(0, 0, 0, 0, 0);

    private final JetStreamManagement jsm;
    private final WebhookProperties properties;
    private final String durablePrefix;
    private final Map<String, Usage> usage = new ConcurrentHashMap<>();
    /** Lowest stream sequence the stream still held, per stream, as of the previous poll. */
    private final Map<String, Long> lastFirstSequence = new ConcurrentHashMap<>();

    private final Map<String, AtomicLong> unacknowledgedGap = new HashMap<>();
    private final Map<String, Counter> dropped = new HashMap<>();
    /**
     * When each stream's accounting last completed. A monitor that cannot read the broker leaves the
     * loss counter flat at zero, which is indistinguishable from no loss — so the age of this is what
     * says whether the counter is currently being maintained at all.
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

            AtomicLong gap = new AtomicLong();
            unacknowledgedGap.put(name, gap);
            Gauge.builder(IntegrationCoreMetrics.WEBHOOK_STREAM_UNACKNOWLEDGED_GAP, gap, AtomicLong::doubleValue)
                    .tags(tags)
                    .register(meterRegistry);
            dropped.put(
                    name,
                    Counter.builder(IntegrationCoreMetrics.WEBHOOK_STREAM_UNACKNOWLEDGED_DELETIONS)
                            .tags(tags)
                            .register(meterRegistry));

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

    /** Package-private so loss accounting is testable without waiting on the scheduler. */
    void poll() {
        for (String name : WebhookJetStreamBootstrap.STREAMS) {
            try {
                StreamInfo info = jsm.getStreamInfo(name);
                StreamState state = info.getStreamState();
                if (state == null) {
                    failed(name, new IllegalStateException("stream state unavailable"));
                    continue;
                }
                usage.put(
                        name,
                        new Usage(
                                state.getByteCount(),
                                state.getMsgCount(),
                                info.getConfiguration().getMaxBytes(),
                                state.getConsumerCount(),
                                ageSeconds(state)));
                accountForLoss(name, state.getFirstSequence());
                Objects.requireNonNull(lastSuccessfulPollMillis.get(name)).set(System.currentTimeMillis());
                recovered(name);
            } catch (Exception e) {
                failed(name, e);
            }
        }
    }

    /**
     * Everything the stream has discarded sits below {@code firstSequence}. Anything in there that a
     * consumer's ack floor has not reached was deleted before that consumer read it.
     */
    private void accountForLoss(String stream, long firstSequence) throws IOException, JetStreamApiException {
        long lastRetained = firstSequence - 1;
        Long previous = lastFirstSequence.get(stream);
        List<ConsumerInfo> consumers = jsm.getConsumers(stream);
        long worstGap = 0;
        long newLoss = 0;
        for (ConsumerInfo consumer : consumers) {
            if (!consumer.getName().startsWith(durablePrefix)) {
                // Another deployment's durable. An abandoned one sits behind firstSequence for good,
                // so counting it pegs the loss counter at a number nobody here can act on.
                continue;
            }
            SequenceInfo ackFloor = consumer.getAckFloor();
            if (ackFloor == null) {
                continue;
            }
            long acked = ackFloor.getStreamSequence();
            long gap = Math.max(0, lastRetained - acked);
            worstGap = Math.max(worstGap, gap);
            if (previous == null) {
                // Nothing to compare against on the first poll of a process, so the standing gap is
                // reported rather than counted: it happened while nothing was watching, and counting
                // it would re-charge the same loss on every restart.
                if (gap > 0) {
                    log.error(
                            "Consumer {} on stream {} is behind the oldest message the stream still holds: {} "
                                    + "webhook(s) were deleted before it read them",
                            consumer.getName(),
                            stream,
                            gap);
                }
                continue;
            }
            // Only sequences that crossed below the retained window since the last poll, and that
            // the consumer had not acknowledged by then, are new loss.
            long alreadyCounted = Math.max(previous - 1, acked);
            long lost = Math.max(0, lastRetained - alreadyCounted);
            if (lost > 0) {
                newLoss += lost;
                log.error(
                        "Stream {} deleted {} unacknowledged webhook(s) that consumer {} had not read. "
                                + "They are not recoverable: raise hephaestus.webhook.stream.max-bytes, lengthen "
                                + "max-age, or find out why the consumer stopped keeping up.",
                        stream,
                        lost,
                        consumer.getName());
            }
        }
        Objects.requireNonNull(unacknowledgedGap.get(stream)).set(worstGap);
        if (newLoss > 0) {
            Objects.requireNonNull(dropped.get(stream)).increment(newLoss);
        }
        lastFirstSequence.put(stream, firstSequence);
    }

    /**
     * The instrument that measures silent loss must not fail silently itself. The first failure and
     * the recovery are both above DEBUG; the repetitions in between are not, so a long broker outage
     * does not bury everything else.
     */
    private void failed(String stream, Exception e) {
        if (failing.put(stream, Boolean.TRUE) == null) {
            log.warn(
                    "Webhook loss accounting stopped for stream {}: the dropped-webhook counter is frozen, "
                            + "not zero, until this recovers ({}: {})",
                    stream,
                    e.getClass().getSimpleName(),
                    e.getMessage());
            return;
        }
        log.debug("Stream usage poll still failing: stream={}, error={}", stream, e.getMessage());
    }

    private void recovered(String stream) {
        if (failing.remove(stream) != null) {
            log.info("Webhook loss accounting resumed for stream {}", stream);
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
    record Usage(long bytes, long messages, long maxBytes, long consumers, long oldestMessageAgeSeconds) {
        double utilization() {
            return maxBytes > 0 ? (double) bytes / maxBytes : 0d;
        }
    }
}
