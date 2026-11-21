package de.tum.cit.aet.hephaestus.integration.core.spi;

import org.jspecify.annotations.Nullable;

/**
 * A provider thread on a work item was resolved or reopened.
 *
 * @param actor the provider id of the account that changed it, or null when the provider's event does not say
 */
public record WorkFeedbackThreadChangedEvent(
        long workspaceId,
        long itemId,
        String threadRef,
        boolean resolved,
        @Nullable String actor) {}
