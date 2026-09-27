package de.tum.cit.aet.hephaestus.integration.core.consumer;

import io.nats.client.Message;
import org.jspecify.annotations.Nullable;

/**
 * Admission for messages the receiver published on an authenticated route. It runs after the delivery is durable, so
 * a failed lookup throws and the message is redelivered rather than dropped.
 */
public interface RouteAdmission {

    /** Whether messages on {@code subject} are admitted here. */
    boolean owns(String subject);

    /**
     * Runs {@code handling} with the route admitted for the scope whose consumer received {@code msg}.
     *
     * @return {@code false} when the route may not be handled for that scope; the message is then acknowledged
     */
    boolean admit(@Nullable Long scopeId, Message msg, Runnable handling);
}
