package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.List;

/**
 * Which connection routes match a provider delivery. The receiver checks their current lifecycle state before it
 * stores anything, in the same transaction as this subscription read.
 */
public interface ProviderEventSubscriptions {

    /** A connection whose configured route matches, before the receiver checks its lifecycle state. */
    record Subscription(long workspaceId, long connectionId) {}

    /** Matching connections in workspace/id order, including inactive ones whose rejection must be recorded. */
    List<Subscription> subscribers(String subject);
}
