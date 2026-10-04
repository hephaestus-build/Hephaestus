package de.tum.cit.aet.hephaestus.integration.core.consumer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * NATS consumer tuning bound to {@code hephaestus.integration.consumer}. Read by every
 * consumer-side collaborator under {@code integration.consumer}.
 *
 * <p>{@code inactive-threshold} lets JetStream delete a durable nothing has been <em>bound</em> to
 * for that long. It measures boundness, not traffic: a running consumer holds standing pull requests
 * and resets the timer continuously even while its stream is silent, so only a durable whose process
 * is gone ages out. Nothing else removes one — a deployment that is deleted rather than shut down
 * never gets to delete its own — and a shared broker therefore accumulates one generation of
 * durables, each with a backlog that will never drain, per stack that ever existed.
 *
 * <p>{@code 0s} disables reaping, for a deployment that may be offline longer than any threshold and
 * must resume exactly where it left off; the cost is that its durables are then permanent, since a
 * reaped durable is recreated at {@link io.nats.client.api.DeliverPolicy#New} and skips whatever
 * arrived while it was gone. {@link #INACTIVE_THRESHOLD_FLOOR} rejects a value short enough that an
 * ordinary restart trips it, which would make the setting the data loss it exists to prevent.
 * Disposable stacks state their own, much shorter value — {@code docker/preview/compose.app.yaml}.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.integration.consumer")
public record NatsConsumerProperties(
        @DurationUnit(ChronoUnit.MINUTES) @DefaultValue("5m") @NotNull(message = "ack-wait must not be null")
        Duration ackWait,

        @DefaultValue("500")
        @Min(value = 1, message = "max-ack-pending must be at least 1")
        @Max(value = 10_000, message = "max-ack-pending must not exceed 10,000")
        int maxAckPending,

        @DurationUnit(ChronoUnit.SECONDS) @DefaultValue("2s") @NotNull(message = "reconnect-delay must not be null")
        Duration reconnectDelay,

        @DurationUnit(ChronoUnit.HOURS)
        @DefaultValue("30d")
        @NotNull(message = "inactive-threshold must not be null")
        @DurationMin(message = "inactive-threshold must not be negative")
        Duration inactiveThreshold,

        @Valid PoisonProperties poison) {
    /**
     * Shortest reapable lifetime. Below this a restart, a deploy or a brief partition can outlast the
     * threshold, and the replacement durable starts at {@link io.nats.client.api.DeliverPolicy#New}.
     * Only {@code 0s}, which disables reaping outright, is allowed below it.
     */
    public static final Duration INACTIVE_THRESHOLD_FLOOR = Duration.ofHours(1);

    public NatsConsumerProperties {
        if (poison == null) {
            poison = new PoisonProperties(10, Duration.ofSeconds(2), Duration.ofMinutes(5));
        }
        if (inactiveThreshold != null
                && !inactiveThreshold.isZero()
                && !inactiveThreshold.isNegative()
                && inactiveThreshold.compareTo(INACTIVE_THRESHOLD_FLOOR) < 0) {
            throw new IllegalArgumentException(
                    "inactive-threshold (" + inactiveThreshold
                            + ") must be 0 to disable reaping, or at least "
                            + INACTIVE_THRESHOLD_FLOOR
                            + ". A shorter value reaps a durable across an ordinary restart. The durable then loses its position.");
        }
    }

    /**
     * Poison-message handling for the consumer's NAK loop.
     *
     * <p>The poison handler NAKs failing messages with exponential backoff up to
     * {@link #maxDelay()}. When a message has been redelivered {@link #maxRedeliver()} times
     * without success it is ACKed and logged at WARN so it stops blocking the consumer's
     * inflight slot.
     *
     * @param maxRedeliver redelivery attempts after which the message is treated as poison
     *                     and ACKed. Default 10.
     * @param baseDelay    starting NAK delay; the actual delay grows exponentially per
     *                     attempt. Default 2s.
     * @param maxDelay     hard cap on the NAK delay. Default 5 minutes — chosen to stay
     *                     below typical ack-wait so the server doesn't redeliver while we
     *                     are still waiting on our own NAK backoff.
     */
    public record PoisonProperties(
            @DefaultValue("10") @Positive(message = "max-redeliver must be positive")
            int maxRedeliver,

            @DurationUnit(ChronoUnit.SECONDS) @DefaultValue("2s") @NotNull(message = "base-delay must not be null")
            Duration baseDelay,

            @DurationUnit(ChronoUnit.MINUTES) @DefaultValue("5m") @NotNull(message = "max-delay must not be null")
            Duration maxDelay) {}
}
