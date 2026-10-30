package de.tum.cit.aet.hephaestus.integration.core.consumer;

import java.time.Duration;

/** Keeps a component added to the record from rippling through every test that builds one. */
final class NatsConsumerPropertiesFixture {

    private NatsConsumerPropertiesFixture() {}

    /** The shipped default, so a test built on {@link #defaults()} sees what a deployment sees. */
    static final Duration SHIPPED_INACTIVE_THRESHOLD = Duration.ofDays(30);

    static NatsConsumerProperties defaults() {
        return withInactiveThreshold(SHIPPED_INACTIVE_THRESHOLD);
    }

    static NatsConsumerProperties withInactiveThreshold(Duration inactiveThreshold) {
        return build(
                inactiveThreshold,
                new NatsConsumerProperties.PoisonProperties(10, Duration.ofSeconds(2), Duration.ofMinutes(5)));
    }

    /** Poison backoff short enough that a redelivery loop finishes inside a unit test. */
    static NatsConsumerProperties withFastPoisonBackoff() {
        return build(
                SHIPPED_INACTIVE_THRESHOLD,
                new NatsConsumerProperties.PoisonProperties(10, Duration.ofMillis(1), Duration.ofSeconds(1)));
    }

    private static NatsConsumerProperties build(
            Duration inactiveThreshold, NatsConsumerProperties.PoisonProperties poison) {
        return new NatsConsumerProperties(Duration.ofMinutes(5), Duration.ofSeconds(2), inactiveThreshold, poison);
    }
}
