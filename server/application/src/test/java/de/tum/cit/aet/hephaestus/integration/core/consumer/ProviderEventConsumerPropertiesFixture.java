package de.tum.cit.aet.hephaestus.integration.core.consumer;

import java.time.Duration;

/** Keeps a component added to the record from rippling through every test that builds one. */
final class ProviderEventConsumerPropertiesFixture {

    private ProviderEventConsumerPropertiesFixture() {}

    static final Duration SHIPPED_HANDLER_TIMEOUT = Duration.ofMinutes(30);

    static ProviderEventConsumerProperties defaults() {
        return build(
                SHIPPED_HANDLER_TIMEOUT,
                new ProviderEventConsumerProperties.Poison(10, Duration.ofSeconds(2), Duration.ofMinutes(5)));
    }

    /** Poison backoff short enough that a retry finishes inside a test. */
    static ProviderEventConsumerProperties withFastPoisonBackoff() {
        return build(
                SHIPPED_HANDLER_TIMEOUT,
                new ProviderEventConsumerProperties.Poison(10, Duration.ofMillis(1), Duration.ofSeconds(1)));
    }

    /** The shipped defaults with a handler timeout that a test can wait out. */
    static ProviderEventConsumerProperties withHandlerTimeout(Duration handlerTimeout) {
        return build(handlerTimeout, defaults().poison());
    }

    private static ProviderEventConsumerProperties build(
            Duration handlerTimeout, ProviderEventConsumerProperties.Poison poison) {
        return new ProviderEventConsumerProperties(handlerTimeout, 2, Duration.ofDays(3), Duration.ofDays(30), poison);
    }
}
