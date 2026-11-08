package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.UUID;

/** Delivery has recorded the provider's facts for this feedback. */
public record FeedbackReadbackObservedEvent(long workspaceId, UUID reviewId) {}
