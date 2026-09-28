package de.tum.cit.aet.hephaestus.integration.core.handler;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * When the webhook being handled on this thread reached the stream. What a webhook says is as of then, not as of
 * when a consumer gets to it: a backlogged or redelivered webhook keeps the time it first arrived, so it does not
 * count as newer than a sync page read after it.
 */
public final class WebhookDelivery {

    private static final ThreadLocal<@Nullable Instant> ARRIVED_AT = new ThreadLocal<>();

    private WebhookDelivery() {}

    /** Handles a delivery that arrived at {@code arrivedAt}. */
    public static void during(Instant arrivedAt, Runnable handling) {
        @Nullable Instant outer = ARRIVED_AT.get();
        ARRIVED_AT.set(arrivedAt);
        try {
            handling.run();
        } finally {
            ARRIVED_AT.set(outer);
        }
    }

    /** When the delivery handled on this thread arrived; empty outside one. */
    public static Optional<Instant> arrivedAt() {
        return Optional.ofNullable(ARRIVED_AT.get());
    }
}
