package de.tum.cit.aet.hephaestus.integration.core.consumer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * How the server handles the provider events in its inbox, bound to {@code hephaestus.integration.consumer}.
 *
 * @param handlerTimeout Longest time that one provider event may take to handle. A handler that takes longer gives its
 *     event back, as a failed attempt that is retried and, after its attempts, kept as a dead letter. The default leaves
 *     room for the first fetch of a large repository (estimated).
 * @param claimers How many events one server handles at once, each of another scope. 0 claims none.
 * @param replayWindow How long a handled event stays, so a provider that sends it again finds it and it is not handled
 *     twice.
 * @param deadRetention How long a dead letter stays for a replay.
 * @param poison The retry budget of a failed event.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.integration.consumer")
public record ProviderEventConsumerProperties(
        @DurationUnit(ChronoUnit.MINUTES) @DefaultValue("30m") @DurationMin(minutes = 1)
        Duration handlerTimeout,

        @DefaultValue("8") @PositiveOrZero int claimers,

        @DurationUnit(ChronoUnit.DAYS) @DefaultValue("3d") @DurationMin(hours = 1)
        Duration replayWindow,

        @DurationUnit(ChronoUnit.DAYS) @DefaultValue("30d") @DurationMin(days = 1)
        Duration deadRetention,

        @DefaultValue @Valid Poison poison) {

    /**
     * @param maxAttempts Attempts of an event whose failure is not known to be operational, before it becomes a dead
     *     letter. A proven operational failure, such as a provider that is down, retries until it recovers.
     * @param baseDelay First wait after a failed attempt; it doubles with each attempt.
     * @param maxDelay Longest wait between two attempts.
     */
    public record Poison(
            @DefaultValue("10") @Positive int maxAttempts,

            @DurationUnit(ChronoUnit.SECONDS) @DefaultValue("2s") @DurationMin(seconds = 1)
            Duration baseDelay,

            @DurationUnit(ChronoUnit.MINUTES) @DefaultValue("5m") @DurationMin(seconds = 1)
            Duration maxDelay) {}
}
